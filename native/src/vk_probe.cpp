// SPDX-License-Identifier: BSD-3-Clause
#include "fhcore/vk_probe.h"

#include <dlfcn.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <set>
#include <string>
#include <vector>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include "fhcore/json.h"

namespace fhcore::vk {

namespace {

std::string VersionString(uint32_t version) {
  char buffer[32];
  std::snprintf(buffer, sizeof(buffer), "%u.%u.%u", VK_API_VERSION_MAJOR(version),
                VK_API_VERSION_MINOR(version), VK_API_VERSION_PATCH(version));
  return buffer;
}

// Qualcomm's proprietary driver packs its version as major<<22|minor<<12|patch
// like the API version; Mesa (Turnip) uses the same macro. Others differ.
std::string DriverVersionString(uint32_t vendor, uint32_t version) {
  char buffer[48];
  if (vendor == 0x10DE) {  // NVIDIA
    std::snprintf(buffer, sizeof(buffer), "%u.%u", version >> 22, (version >> 14) & 0xFF);
  } else {
    std::snprintf(buffer, sizeof(buffer), "%u.%u.%u", VK_API_VERSION_MAJOR(version),
                  VK_API_VERSION_MINOR(version), VK_API_VERSION_PATCH(version));
  }
  return buffer;
}

struct Loader {
  void* library = nullptr;
  PFN_vkGetInstanceProcAddr get_instance_proc = nullptr;
  ~Loader() {
    if (library) dlclose(library);
  }
  bool Open(std::string& error) {
    for (const char* name : {"libvulkan.so", "libvulkan.so.1"}) {
      library = dlopen(name, RTLD_NOW | RTLD_LOCAL);
      if (library) break;
    }
    if (!library) {
      error = "the Vulkan loader (libvulkan.so) is not available";
      return false;
    }
    get_instance_proc =
        reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
    if (!get_instance_proc) {
      error = "libvulkan.so has no vkGetInstanceProcAddr";
      return false;
    }
    return true;
  }
};

}  // namespace

std::string ProbeJson() {
  json::ObjectWriter out;
  Loader loader;
  std::string error;
  if (!loader.Open(error)) {
    out.Bool("ok", false).String("error", error);
    return out.Finish();
  }
  auto gip = loader.get_instance_proc;
  auto enumerate_version =
      reinterpret_cast<PFN_vkEnumerateInstanceVersion>(gip(nullptr, "vkEnumerateInstanceVersion"));
  uint32_t instance_version = VK_API_VERSION_1_0;
  if (enumerate_version) enumerate_version(&instance_version);

  auto create_instance =
      reinterpret_cast<PFN_vkCreateInstance>(gip(nullptr, "vkCreateInstance"));
  if (!create_instance) {
    out.Bool("ok", false).String("error", "vkCreateInstance missing");
    return out.Finish();
  }
  VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
  app.pApplicationName = "FHorizon Mobile probe";
  app.apiVersion = instance_version >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_3 : VK_API_VERSION_1_0;
  VkInstanceCreateInfo instance_info = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  instance_info.pApplicationInfo = &app;
  VkInstance instance = VK_NULL_HANDLE;
  VkResult result = create_instance(&instance_info, nullptr, &instance);
  if (result != VK_SUCCESS) {
    out.Bool("ok", false).String("error", "vkCreateInstance failed (" + std::to_string(result) + ")");
    return out.Finish();
  }

#define LOAD(name) auto name = reinterpret_cast<PFN_##name>(gip(instance, #name))
  LOAD(vkDestroyInstance);
  LOAD(vkEnumeratePhysicalDevices);
  LOAD(vkGetPhysicalDeviceProperties);
  LOAD(vkGetPhysicalDeviceProperties2);
  LOAD(vkGetPhysicalDeviceFeatures);
  LOAD(vkGetPhysicalDeviceFormatProperties);
  LOAD(vkGetPhysicalDeviceMemoryProperties);
  LOAD(vkEnumerateDeviceExtensionProperties);
#undef LOAD

  uint32_t count = 0;
  vkEnumeratePhysicalDevices(instance, &count, nullptr);
  std::vector<VkPhysicalDevice> devices(count);
  if (count) vkEnumeratePhysicalDevices(instance, &count, devices.data());

  json::ArrayWriter device_array;
  bool any_usable = false;
  for (VkPhysicalDevice device : devices) {
    VkPhysicalDeviceProperties props = {};
    vkGetPhysicalDeviceProperties(device, &props);
    VkPhysicalDeviceFeatures features = {};
    vkGetPhysicalDeviceFeatures(device, &features);

    std::string driver_name, driver_info;
    if (vkGetPhysicalDeviceProperties2 && props.apiVersion >= VK_API_VERSION_1_2) {
      VkPhysicalDeviceDriverProperties driver = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES};
      VkPhysicalDeviceProperties2 props2 = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
      props2.pNext = &driver;
      vkGetPhysicalDeviceProperties2(device, &props2);
      driver_name = driver.driverName;
      driver_info = driver.driverInfo;
    }

    uint32_t extension_count = 0;
    vkEnumerateDeviceExtensionProperties(device, nullptr, &extension_count, nullptr);
    std::vector<VkExtensionProperties> extensions(extension_count);
    if (extension_count) {
      vkEnumerateDeviceExtensionProperties(device, nullptr, &extension_count, extensions.data());
    }
    std::set<std::string> extension_names;
    for (const auto& e : extensions) extension_names.insert(e.extensionName);

    const auto format_features = [&](VkFormat format) {
      VkFormatProperties fp = {};
      vkGetPhysicalDeviceFormatProperties(device, format, &fp);
      return fp.optimalTilingFeatures;
    };
    constexpr VkFormatFeatureFlags kDepth =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT;
    constexpr VkFormatFeatureFlags kGuestOutput = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                                  VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT |
                                                  VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    const bool depth24 = (format_features(VK_FORMAT_D24_UNORM_S8_UINT) & kDepth) == kDepth;
    const bool depth32 = (format_features(VK_FORMAT_D32_SFLOAT_S8_UINT) & kDepth) == kDepth;
    const bool guest_output =
        (format_features(VK_FORMAT_A2B10G10R10_UNORM_PACK32) & kGuestOutput) == kGuestOutput;

    // Hard requirements: the renderer refuses the device without these.
    json::ArrayWriter blocking;
    int blocking_count = 0;
    const auto block = [&](const char* text) {
      blocking.String(text);
      ++blocking_count;
    };
    if (!features.independentBlend) block("independentBlend");
    if (!features.fragmentStoresAndAtomics) block("fragmentStoresAndAtomics");
    if (!features.vertexPipelineStoresAndAtomics) block("vertexPipelineStoresAndAtomics");
    if (!depth24 && !depth32) block("depth format D24_UNORM_S8_UINT or D32_SFLOAT_S8_UINT");
    if (!guest_output) block("A2B10G10R10_UNORM sampled + color + storage");
    if (!extension_names.count("VK_KHR_swapchain")) block("VK_KHR_swapchain");

    // Features the renderer works around, at a cost.
    json::ArrayWriter fallbacks;
    if (!depth24) fallbacks.String("D24_UNORM_S8_UINT: depth kept in D32_SFLOAT_S8_UINT");
    if (!features.textureCompressionBC)
      fallbacks.String("textureCompressionBC: BC textures decoded to RGBA8 (4-8x memory)");
    if (!features.geometryShader)
      fallbacks.String("geometryShader: rectangle/point lists expanded in the vertex shader");
    if (!features.sparseBinding)
      fallbacks.String("sparseBinding: shared memory as one 512 MB allocation");
    if (!features.samplerAnisotropy) fallbacks.String("samplerAnisotropy: no anisotropic filtering");
    if (props.apiVersion < VK_API_VERSION_1_3)
      fallbacks.String("Vulkan < 1.3: Pinyon Shift's reference devices all run 1.3 drivers; "
                       "an older driver is untested");

    json::ObjectWriter feature_object;
    feature_object.Bool("independentBlend", features.independentBlend)
        .Bool("geometryShader", features.geometryShader)
        .Bool("fragmentStoresAndAtomics", features.fragmentStoresAndAtomics)
        .Bool("vertexPipelineStoresAndAtomics", features.vertexPipelineStoresAndAtomics)
        .Bool("textureCompressionBC", features.textureCompressionBC)
        .Bool("textureCompressionETC2", features.textureCompressionETC2)
        .Bool("textureCompressionASTC_LDR", features.textureCompressionASTC_LDR)
        .Bool("sparseBinding", features.sparseBinding)
        .Bool("sparseResidencyBuffer", features.sparseResidencyBuffer)
        .Bool("samplerAnisotropy", features.samplerAnisotropy)
        .Bool("shaderClipDistance", features.shaderClipDistance)
        .Bool("depthClamp", features.depthClamp)
        .Bool("fillModeNonSolid", features.fillModeNonSolid)
        .Bool("sampleRateShading", features.sampleRateShading)
        .Bool("shaderStorageImageMultisample", features.shaderStorageImageMultisample)
        .Bool("fullDrawIndexUint32", features.fullDrawIndexUint32)
        .Bool("imageCubeArray", features.imageCubeArray);

    json::ObjectWriter has;
    for (const char* name :
         {"VK_KHR_swapchain", "VK_EXT_memory_budget", "VK_KHR_fragment_shading_rate",
          "VK_EXT_multisampled_render_to_single_sampled", "VK_KHR_dynamic_rendering",
          "VK_EXT_fragment_shader_interlock", "VK_KHR_push_descriptor",
          "VK_KHR_timeline_semaphore", "VK_EXT_descriptor_indexing",
          "VK_KHR_shader_float16_int8", "VK_KHR_present_wait", "VK_GOOGLE_display_timing"}) {
      has.Bool(name, extension_names.count(name) != 0);
    }

    VkPhysicalDeviceMemoryProperties memory = {};
    vkGetPhysicalDeviceMemoryProperties(device, &memory);
    uint64_t device_local = 0;
    for (uint32_t i = 0; i < memory.memoryHeapCount; ++i) {
      if (memory.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) {
        device_local = std::max<uint64_t>(device_local, memory.memoryHeaps[i].size);
      }
    }

    const bool usable = blocking_count == 0;
    any_usable |= usable;
    json::ObjectWriter entry;
    entry.String("name", props.deviceName)
        .String("api_version", VersionString(props.apiVersion))
        .Unsigned("api_version_raw", props.apiVersion)
        .String("driver_version", DriverVersionString(props.vendorID, props.driverVersion))
        .Unsigned("driver_version_raw", props.driverVersion)
        .String("driver_name", driver_name)
        .String("driver_info", driver_info)
        .Unsigned("vendor_id", props.vendorID)
        .Unsigned("device_id", props.deviceID)
        .Unsigned("max_image_dimension_2d", props.limits.maxImageDimension2D)
        .Double("max_sampler_anisotropy", props.limits.maxSamplerAnisotropy)
        .Unsigned("framebuffer_color_sample_counts", props.limits.framebufferColorSampleCounts)
        .Unsigned("device_local_heap_bytes", device_local)
        .Bool("hard_requirements_met", usable)
        .Raw("blocking", blocking.Finish())
        .Raw("fallbacks", fallbacks.Finish())
        .Raw("features", feature_object.Finish())
        .Raw("has", has.Finish())
        .Unsigned("extensions_count", extension_count);
    device_array.Raw(entry.Finish());
  }
  vkDestroyInstance(instance, nullptr);

  out.Bool("ok", true)
      .String("instance_api", VersionString(instance_version))
      .Bool("any_device_usable", any_usable)
      .Raw("devices", device_array.Finish());
  return out.Finish();
}

}  // namespace fhcore::vk

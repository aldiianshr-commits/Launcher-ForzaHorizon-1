// FHorizon Mobile - Vulkan capability probe.
//
// Checks the device's Vulkan driver against what the ShiftGlue renderer
// (ReXGlue's Xenos-to-Vulkan GPU backend, derived from Xenia) refuses to run
// without, and reports the optional features it falls back from. The hard
// requirements mirror VulkanDevice::CreateIfSupported and the capability
// report in ShiftGlue's src/ui/vulkan/vulkan_device.cpp.
//
// SPDX-License-Identifier: BSD-3-Clause
#pragma once

#include <string>

namespace fhcore::vk {

// Opens the system Vulkan loader (libvulkan.so), enumerates the physical
// devices and returns a JSON report:
// {"ok":bool,"error":?, "instance_api":"1.x.y", "devices":[{"name","api_version",
//  "driver_version","driver_name","driver_info","vendor_id","device_id",
//  "hard_requirements_met":bool,"blocking":[...],"fallbacks":[...],
//  "features":{...},"formats":{...},"extensions_count":n, "has":{ext:bool}}]}
std::string ProbeJson();

}  // namespace fhcore::vk

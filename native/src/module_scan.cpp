// SPDX-License-Identifier: BSD-3-Clause
#include "fhcore/module_scan.h"

#include <algorithm>
#include <filesystem>
#include <set>
#include <vector>

#include "fhcore/elf_module.h"
#include "fhcore/json.h"

namespace fhcore {

std::string InspectModuleDirectory(const std::string& directory, const std::string& entry,
                                   uint16_t expected_machine) {
  namespace fs = std::filesystem;
  std::vector<std::string> paths;
  std::error_code error;
  for (auto it = fs::directory_iterator(directory, error);
       !error && it != fs::directory_iterator(); it.increment(error)) {
    if (it->is_regular_file(error) && it->path().extension() == ".so") {
      paths.push_back(it->path().string());
    }
  }
  std::sort(paths.begin(), paths.end());
  // SDL_main in the game, the GPU plugin's entry points in librexgpu-*.so.
  const std::set<std::string> interesting = {"SDL_main", "JNI_OnLoad", "rex_gpu_abi_version",
                                             "rex_gpu_create", "SDL_GetVersion",
                                             "adrenotools_open_libvulkan"};
  std::vector<elf::Library> libraries;
  json::ArrayWriter array;
  for (const auto& path : paths) {
    libraries.push_back(elf::Inspect(path, interesting));
    array.Raw(elf::ToJson(libraries.back()));
  }
  const elf::ModulePlan plan = elf::PlanModule(libraries, entry, expected_machine);
  json::ObjectWriter out;
  if (error) out.String("directory_error", error.message());
  out.Raw("libraries", array.Finish()).Raw("plan", elf::ToJson(plan));
  return out.Finish();
}

}  // namespace fhcore

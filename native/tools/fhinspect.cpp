// fhinspect - command-line front end of the FHorizon Mobile inspectors, for
// a PC: check a default.xex or a built backend module before copying it to
// the tablet.
//
//   fhinspect xex <default.xex>
//   fhinspect module <folder with .so files> [entry=libmain.so]
//   fhinspect vulkan
//
// SPDX-License-Identifier: BSD-3-Clause
#include <cstdio>
#include <cstring>
#include <string>

#include "fhcore/elf_module.h"
#include "fhcore/module_scan.h"
#include "fhcore/xex.h"
#if FHCORE_HAVE_VULKAN
#include "fhcore/vk_probe.h"
#endif

int main(int argc, char** argv) {
  if (argc >= 3 && std::strcmp(argv[1], "xex") == 0) {
    const auto result = fhcore::xex::ParseFile(argv[2]);
    std::printf("%s\n", fhcore::xex::ToJson(result).c_str());
    return result.ok ? 0 : 1;
  }
  if (argc >= 3 && std::strcmp(argv[1], "module") == 0) {
    const std::string entry = argc >= 4 ? argv[3] : "libmain.so";
    std::printf("%s\n",
                fhcore::InspectModuleDirectory(argv[2], entry, fhcore::elf::kMachineAArch64).c_str());
    return 0;
  }
#if FHCORE_HAVE_VULKAN
  if (argc >= 2 && std::strcmp(argv[1], "vulkan") == 0) {
    std::printf("%s\n", fhcore::vk::ProbeJson().c_str());
    return 0;
  }
#endif
  std::fprintf(stderr,
               "usage:\n  %s xex <default.xex>\n  %s module <folder> [entry=libmain.so]\n"
#if FHCORE_HAVE_VULKAN
               "  %s vulkan\n"
#endif
               ,
               argv[0], argv[0]
#if FHCORE_HAVE_VULKAN
               ,
               argv[0]
#endif
  );
  return 2;
}

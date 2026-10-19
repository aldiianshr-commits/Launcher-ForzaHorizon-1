// FHorizon Mobile - inspects every shared library in a module folder.
// SPDX-License-Identifier: BSD-3-Clause
#pragma once

#include <cstdint>
#include <string>

namespace fhcore {

// {"libraries":[...per library...],"plan":{...load order...}} for the .so
// files directly inside `directory`, planned from `entry` (libmain.so).
std::string InspectModuleDirectory(const std::string& directory, const std::string& entry,
                                   uint16_t expected_machine);

}  // namespace fhcore

// FHorizon Mobile - ELF shared-library inspector for backend modules.
//
// A backend module is a set of AArch64 shared libraries built on the
// player's PC (the recompiled game, the ReXGlue/ShiftGlue runtime, the GPU
// plugin and helpers). Before installing one, the launcher checks each
// library's architecture, its DT_SONAME and DT_NEEDED entries, its load
// segment alignment and the symbols the loader needs (SDL_main in the game
// library), and computes the order in which System.load must load them so
// that every DT_NEEDED is already resolved by soname.
//
// SPDX-License-Identifier: BSD-3-Clause
#pragma once

#include <cstdint>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

namespace fhcore::elf {

constexpr uint16_t kMachineAArch64 = 183;
constexpr uint16_t kMachineX86_64 = 62;

struct Library {
  std::string file_name;  // as found on disk
  bool ok = false;
  std::string error;
  uint16_t machine = 0;
  uint16_t type = 0;            // ET_DYN = 3
  std::string soname;           // DT_SONAME, or empty
  std::vector<std::string> needed;  // DT_NEEDED
  uint64_t min_load_align = 0;  // smallest PT_LOAD p_align
  uint64_t file_size = 0;
  size_t dynamic_symbol_count = 0;
  std::set<std::string> exports;  // defined global/weak dynamic symbols (filtered)
  // "3.5.0" when the library embeds SDL's revision string ("SDL-3.5.0-..."),
  // which is how the launcher checks that its SDL Java classes match the
  // SDL compiled into the module (SDL refuses to start on a mismatch).
  std::string sdl_version;

  std::string machine_name() const;
  // The name other libraries use for this one in DT_NEEDED.
  std::string link_name() const { return soname.empty() ? file_name : soname; }
};

// Reads a shared library. `interesting_symbols` limits which exported
// names are collected (all are counted); empty collects none.
Library Inspect(const std::string& path, const std::set<std::string>& interesting_symbols);
Library InspectBuffer(const std::vector<uint8_t>& data, const std::string& file_name,
                      const std::set<std::string>& interesting_symbols);

// Libraries Android provides to apps (NDK stable API and common vendor
// paths). A module may depend on these; anything else must ship inside it.
bool IsSystemLibrary(const std::string& name);

struct ModulePlan {
  bool ok = false;
  std::vector<std::string> errors;
  std::vector<std::string> warnings;
  // File names in the order System.load must load them (dependencies first);
  // only libraries reachable from the entry library are included.
  std::vector<std::string> load_order;
  // Libraries in the module that nothing loads at start (GPU plugin, driver
  // hooks, FidelityFX) - the runtime opens them itself by path.
  std::vector<std::string> side_libraries;
  std::string entry_library;
};

// Validates a module (all libraries already inspected) and plans its load
// order from `entry` (normally libmain.so). `expected_machine` is AArch64 on
// devices; tests pass the host's machine.
ModulePlan PlanModule(const std::vector<Library>& libraries, const std::string& entry,
                      uint16_t expected_machine);

std::string ToJson(const Library& library);
std::string ToJson(const ModulePlan& plan);

}  // namespace fhcore::elf

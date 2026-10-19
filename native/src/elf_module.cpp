// SPDX-License-Identifier: BSD-3-Clause
#include "fhcore/elf_module.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <functional>

#include "fhcore/json.h"

namespace fhcore::elf {

namespace {

// ELF64 little-endian constants.
constexpr uint32_t PT_LOAD = 1;
constexpr uint32_t PT_DYNAMIC = 2;
constexpr int64_t DT_NULL = 0;
constexpr int64_t DT_NEEDED = 1;
constexpr int64_t DT_HASH = 4;
constexpr int64_t DT_STRTAB = 5;
constexpr int64_t DT_SYMTAB = 6;
constexpr int64_t DT_STRSZ = 10;
constexpr int64_t DT_SONAME = 14;
constexpr int64_t DT_GNU_HASH = 0x6ffffef5;
constexpr uint32_t SHT_DYNSYM = 11;
constexpr uint16_t SHN_UNDEF = 0;

struct View {
  const uint8_t* data = nullptr;
  size_t size = 0;
  bool Has(uint64_t offset, uint64_t length) const {
    return offset <= size && length <= size - offset;
  }
  template <typename T>
  T Get(uint64_t offset) const {
    T value;
    std::memcpy(&value, data + offset, sizeof(T));
    return value;
  }
  std::string CString(uint64_t offset, uint64_t limit) const {
    std::string out;
    while (offset < size && offset < limit && data[offset]) out.push_back(char(data[offset++]));
    return out;
  }
};

struct Segment {
  uint64_t vaddr, offset, filesz;
};

std::optional<uint64_t> VaddrToOffset(const std::vector<Segment>& loads, uint64_t vaddr) {
  for (const Segment& s : loads) {
    if (vaddr >= s.vaddr && vaddr < s.vaddr + s.filesz) return vaddr - s.vaddr + s.offset;
  }
  return std::nullopt;
}

// The version in SDL's revision string, "SDL-<major>.<minor>.<patch>-...".
std::string FindSdlVersion(const View& v) {
  static const char kTag[] = "SDL-";
  const uint8_t* at = v.data;
  const uint8_t* end = v.data + v.size;
  while (at + 10 < end) {
    const void* hit = memchr(at, 'S', size_t(end - at));
    if (!hit) break;
    at = static_cast<const uint8_t*>(hit);
    if (size_t(end - at) > 10 && std::memcmp(at, kTag, 4) == 0) {
      const uint8_t* p = at + 4;
      std::string version;
      int dots = 0;
      while (p < end && version.size() < 16 && ((*p >= '0' && *p <= '9') || *p == '.')) {
        if (*p == '.') ++dots;
        version.push_back(char(*p++));
      }
      if (dots == 2 && p < end && (*p == '-' || *p == 0) && version.front() != '.' &&
          version.back() != '.') {
        return version;
      }
    }
    ++at;
  }
  return {};
}

void InspectView(const View& v, Library& lib, const std::set<std::string>& interesting) {
  if (!v.Has(0, 64) || std::memcmp(v.data, "\x7f" "ELF", 4) != 0) {
    lib.error = "not an ELF file";
    return;
  }
  if (v.data[4] != 2) {
    lib.error = "not a 64-bit ELF (32-bit libraries cannot run in an arm64 process)";
    return;
  }
  if (v.data[5] != 1) {
    lib.error = "not a little-endian ELF";
    return;
  }
  lib.type = v.Get<uint16_t>(0x10);
  lib.machine = v.Get<uint16_t>(0x12);
  const uint64_t phoff = v.Get<uint64_t>(0x20);
  const uint64_t shoff = v.Get<uint64_t>(0x28);
  const uint16_t phentsize = v.Get<uint16_t>(0x36);
  const uint16_t phnum = v.Get<uint16_t>(0x38);
  const uint16_t shentsize = v.Get<uint16_t>(0x3A);
  const uint16_t shnum = v.Get<uint16_t>(0x3C);
  if (lib.type != 3) {
    lib.error = "not a shared library (ELF type " + std::to_string(lib.type) + ")";
    return;
  }
  if (phentsize < 56 || !v.Has(phoff, uint64_t(phentsize) * phnum)) {
    lib.error = "program headers out of bounds";
    return;
  }

  std::vector<Segment> loads;
  uint64_t dynamic_offset = 0, dynamic_size = 0;
  for (uint16_t i = 0; i < phnum; ++i) {
    const uint64_t at = phoff + uint64_t(i) * phentsize;
    const uint32_t p_type = v.Get<uint32_t>(at);
    const uint64_t p_offset = v.Get<uint64_t>(at + 8);
    const uint64_t p_vaddr = v.Get<uint64_t>(at + 16);
    const uint64_t p_filesz = v.Get<uint64_t>(at + 32);
    const uint64_t p_align = v.Get<uint64_t>(at + 48);
    if (p_type == PT_LOAD) {
      loads.push_back({p_vaddr, p_offset, p_filesz});
      if (lib.min_load_align == 0 || p_align < lib.min_load_align) lib.min_load_align = p_align;
    } else if (p_type == PT_DYNAMIC) {
      dynamic_offset = p_offset;
      dynamic_size = p_filesz;
    }
  }
  if (dynamic_size == 0 || !v.Has(dynamic_offset, dynamic_size)) {
    lib.error = "no dynamic section";
    return;
  }

  uint64_t strtab = 0, strsz = 0, symtab = 0, hash = 0, gnu_hash = 0;
  std::vector<uint64_t> needed_offsets;
  std::optional<uint64_t> soname_offset;
  for (uint64_t at = dynamic_offset; at + 16 <= dynamic_offset + dynamic_size; at += 16) {
    const int64_t tag = v.Get<int64_t>(at);
    const uint64_t value = v.Get<uint64_t>(at + 8);
    if (tag == DT_NULL) break;
    switch (tag) {
      case DT_NEEDED: needed_offsets.push_back(value); break;
      case DT_SONAME: soname_offset = value; break;
      case DT_STRTAB: strtab = value; break;
      case DT_STRSZ: strsz = value; break;
      case DT_SYMTAB: symtab = value; break;
      case DT_HASH: hash = value; break;
      case DT_GNU_HASH: gnu_hash = value; break;
      default: break;
    }
  }
  const auto strtab_offset = VaddrToOffset(loads, strtab);
  if (!strtab_offset || !v.Has(*strtab_offset, strsz)) {
    lib.error = "dynamic string table out of bounds";
    return;
  }
  const uint64_t str_limit = *strtab_offset + strsz;
  for (uint64_t offset : needed_offsets) {
    if (offset < strsz) lib.needed.push_back(v.CString(*strtab_offset + offset, str_limit));
  }
  if (soname_offset && *soname_offset < strsz) {
    lib.soname = v.CString(*strtab_offset + *soname_offset, str_limit);
  }

  // The dynamic symbol table: its size from the section headers when they
  // are present, else from DT_HASH or a walk of DT_GNU_HASH.
  const auto symtab_offset = VaddrToOffset(loads, symtab);
  size_t count = 0;
  if (shentsize >= 64 && shnum > 0 && v.Has(shoff, uint64_t(shentsize) * shnum)) {
    for (uint16_t i = 0; i < shnum; ++i) {
      const uint64_t at = shoff + uint64_t(i) * shentsize;
      if (v.Get<uint32_t>(at + 4) == SHT_DYNSYM) {
        const uint64_t size = v.Get<uint64_t>(at + 32);
        const uint64_t entsize = v.Get<uint64_t>(at + 56);
        if (entsize) count = size / entsize;
        break;
      }
    }
  }
  if (count == 0 && hash) {
    if (auto offset = VaddrToOffset(loads, hash); offset && v.Has(*offset, 8)) {
      count = v.Get<uint32_t>(*offset + 4);  // nchain
    }
  }
  if (count == 0 && gnu_hash) {
    if (auto offset = VaddrToOffset(loads, gnu_hash); offset && v.Has(*offset, 16)) {
      const uint32_t nbuckets = v.Get<uint32_t>(*offset);
      const uint32_t symoffset = v.Get<uint32_t>(*offset + 4);
      const uint32_t bloom_size = v.Get<uint32_t>(*offset + 8);
      const uint64_t buckets = *offset + 16 + uint64_t(bloom_size) * 8;
      const uint64_t chains = buckets + uint64_t(nbuckets) * 4;
      uint32_t last = 0;
      if (v.Has(buckets, uint64_t(nbuckets) * 4)) {
        for (uint32_t b = 0; b < nbuckets; ++b) last = std::max(last, v.Get<uint32_t>(buckets + b * 4ull));
      }
      if (last >= symoffset) {
        uint64_t at = chains + uint64_t(last - symoffset) * 4;
        while (v.Has(at, 4) && !(v.Get<uint32_t>(at) & 1)) {
          ++last;
          at += 4;
        }
        count = last + 1;
      } else {
        count = symoffset;
      }
    }
  }
  lib.dynamic_symbol_count = count;
  lib.sdl_version = FindSdlVersion(v);
  if (symtab_offset && count && v.Has(*symtab_offset, uint64_t(count) * 24)) {
    for (size_t i = 1; i < count; ++i) {
      const uint64_t at = *symtab_offset + i * 24;
      const uint32_t name = v.Get<uint32_t>(at);
      const uint8_t info = v.data[at + 4];
      const uint16_t shndx = v.Get<uint16_t>(at + 6);
      const uint8_t binding = info >> 4;
      if (shndx == SHN_UNDEF || (binding != 1 && binding != 2) || name >= strsz) continue;
      if (interesting.empty()) continue;
      std::string symbol = v.CString(*strtab_offset + name, str_limit);
      if (interesting.count(symbol)) lib.exports.insert(std::move(symbol));
    }
  }
  lib.ok = true;
}

}  // namespace

std::string Library::machine_name() const {
  switch (machine) {
    case kMachineAArch64: return "aarch64";
    case kMachineX86_64: return "x86_64";
    case 40: return "arm (32-bit)";
    case 3: return "x86 (32-bit)";
    default: return "machine " + std::to_string(machine);
  }
}

Library InspectBuffer(const std::vector<uint8_t>& data, const std::string& file_name,
                      const std::set<std::string>& interesting) {
  Library lib;
  lib.file_name = file_name;
  lib.file_size = data.size();
  InspectView({data.data(), data.size()}, lib, interesting);
  return lib;
}

Library Inspect(const std::string& path, const std::set<std::string>& interesting) {
  Library lib;
  const size_t slash = path.find_last_of('/');
  lib.file_name = slash == std::string::npos ? path : path.substr(slash + 1);
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    lib.error = "cannot open " + path + ": " + std::strerror(errno);
    return lib;
  }
  struct stat st {};
  if (fstat(fd, &st) != 0 || st.st_size <= 0) {
    close(fd);
    lib.error = "cannot stat " + path;
    return lib;
  }
  lib.file_size = uint64_t(st.st_size);
  void* map = mmap(nullptr, size_t(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (map == MAP_FAILED) {
    lib.error = "cannot map " + path + ": " + std::strerror(errno);
    return lib;
  }
  InspectView({static_cast<const uint8_t*>(map), size_t(st.st_size)}, lib, interesting);
  munmap(map, size_t(st.st_size));
  return lib;
}

bool IsSystemLibrary(const std::string& name) {
  static const std::set<std::string> kSystem = {
      "libc.so",       "libm.so",          "libdl.so",        "liblog.so",
      "libandroid.so", "libvulkan.so",     "libEGL.so",       "libGLESv1_CM.so",
      "libGLESv2.so",  "libGLESv3.so",     "libjnigraphics.so", "libOpenSLES.so",
      "libaaudio.so",  "libamidi.so",      "libcamera2ndk.so", "libmediandk.so",
      "libnativewindow.so", "libneuralnetworks.so", "libsync.so", "libz.so",
      "libstdc++.so",  "libOpenMAXAL.so",  "libbinder_ndk.so", "libicu.so",
  };
  return kSystem.count(name) != 0;
}

ModulePlan PlanModule(const std::vector<Library>& libraries, const std::string& entry,
                      uint16_t expected_machine) {
  ModulePlan plan;
  plan.entry_library = entry;
  std::map<std::string, const Library*> by_link_name;
  std::map<std::string, const Library*> by_file;
  for (const Library& lib : libraries) {
    by_file[lib.file_name] = &lib;
    if (!lib.ok) {
      plan.errors.push_back(lib.file_name + ": " + lib.error);
      continue;
    }
    if (lib.machine != expected_machine) {
      plan.errors.push_back(lib.file_name + ": built for " + lib.machine_name() +
                            ", the device needs aarch64");
    }
    by_link_name[lib.link_name()] = &lib;
    by_link_name.emplace(lib.file_name, &lib);
    if (lib.min_load_align && lib.min_load_align < 0x1000) {
      plan.errors.push_back(lib.file_name + ": load segments aligned below 4 KiB");
    } else if (lib.min_load_align && lib.min_load_align < 0x4000) {
      plan.warnings.push_back(lib.file_name +
                              ": load segments aligned below 16 KiB (fine on 4 KiB-page "
                              "devices such as the Xiaomi Pad 6)");
    }
  }
  const auto entry_it = by_file.find(entry);
  if (entry_it == by_file.end()) {
    plan.errors.push_back("the module has no " + entry);
    return plan;
  }
  if (entry_it->second->ok && !entry_it->second->exports.count("SDL_main")) {
    plan.errors.push_back(entry + " does not export SDL_main");
  }

  // Depth-first post-order over DT_NEEDED: dependencies before dependents.
  std::set<std::string> visiting, done;
  std::function<void(const Library&)> visit = [&](const Library& lib) {
    if (done.count(lib.file_name)) return;
    if (visiting.count(lib.file_name)) {
      plan.errors.push_back("dependency cycle through " + lib.file_name);
      return;
    }
    visiting.insert(lib.file_name);
    for (const std::string& needed : lib.needed) {
      const auto it = by_link_name.find(needed);
      if (it != by_link_name.end()) {
        visit(*it->second);
      } else if (!IsSystemLibrary(needed)) {
        plan.errors.push_back(lib.file_name + " needs " + needed +
                              ", which is neither in the module nor an Android system library");
      }
    }
    visiting.erase(lib.file_name);
    done.insert(lib.file_name);
    plan.load_order.push_back(lib.file_name);
  };
  if (entry_it->second->ok) visit(*entry_it->second);
  for (const Library& lib : libraries) {
    if (lib.ok && !done.count(lib.file_name)) plan.side_libraries.push_back(lib.file_name);
  }
  plan.ok = plan.errors.empty();
  return plan;
}

std::string ToJson(const Library& lib) {
  json::ObjectWriter out;
  out.String("file", lib.file_name).Bool("ok", lib.ok);
  if (!lib.ok) {
    out.String("error", lib.error);
    return out.Finish();
  }
  json::ArrayWriter needed;
  for (const auto& name : lib.needed) needed.String(name);
  json::ArrayWriter exports;
  for (const auto& name : lib.exports) exports.String(name);
  out.String("machine", lib.machine_name())
      .String("soname", lib.soname)
      .Raw("needed", needed.Finish())
      .Unsigned("min_load_align", lib.min_load_align)
      .Unsigned("size", lib.file_size)
      .Unsigned("dynamic_symbols", lib.dynamic_symbol_count)
      .String("sdl_version", lib.sdl_version)
      .Raw("exports", exports.Finish());
  return out.Finish();
}

std::string ToJson(const ModulePlan& plan) {
  json::ArrayWriter order, side, errors, warnings;
  for (const auto& name : plan.load_order) order.String(name);
  for (const auto& name : plan.side_libraries) side.String(name);
  for (const auto& text : plan.errors) errors.String(text);
  for (const auto& text : plan.warnings) warnings.String(text);
  json::ObjectWriter out;
  out.Bool("ok", plan.ok)
      .String("entry", plan.entry_library)
      .Raw("load_order", order.Finish())
      .Raw("side_libraries", side.Finish())
      .Raw("errors", errors.Finish())
      .Raw("warnings", warnings.Finish());
  return out.Finish();
}

}  // namespace fhcore::elf

// Host tests for fhcore (run by CTest locally and in CI).
// SPDX-License-Identifier: BSD-3-Clause
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <string>
#include <vector>

#include "fhcore/elf_module.h"
#include "fhcore/json.h"
#include "fhcore/module_scan.h"
#include "fhcore/xex.h"

namespace {

int g_failures = 0;
int g_checks = 0;

#define CHECK(condition)                                                        \
  do {                                                                          \
    ++g_checks;                                                                 \
    if (!(condition)) {                                                         \
      ++g_failures;                                                             \
      std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #condition); \
    }                                                                           \
  } while (0)

#define CHECK_EQ(a, b)                                                          \
  do {                                                                          \
    ++g_checks;                                                                 \
    if (!((a) == (b))) {                                                        \
      ++g_failures;                                                             \
      std::fprintf(stderr, "%s:%d: CHECK_EQ failed: %s vs %s\n", __FILE__, __LINE__, #a, #b); \
    }                                                                           \
  } while (0)

// Big-endian buffer builder for synthetic XEX2 headers. No game data is
// used: every value here is made up for the test.
struct Be {
  std::vector<uint8_t> bytes;
  size_t size() const { return bytes.size(); }
  void Pad(size_t to) {
    if (bytes.size() < to) bytes.resize(to, 0);
  }
  void U8(size_t at, uint8_t v) {
    Pad(at + 1);
    bytes[at] = v;
  }
  void U16(size_t at, uint16_t v) {
    Pad(at + 2);
    bytes[at] = uint8_t(v >> 8);
    bytes[at + 1] = uint8_t(v);
  }
  void U32(size_t at, uint32_t v) {
    Pad(at + 4);
    for (int i = 0; i < 4; ++i) bytes[at + i] = uint8_t(v >> (24 - 8 * i));
  }
  void Str(size_t at, const std::string& s) {
    Pad(at + s.size() + 1);
    std::memcpy(bytes.data() + at, s.data(), s.size());
    bytes[at + s.size()] = 0;
  }
};

Be MakeXex() {
  Be x;
  const uint32_t kHeaderCount = 7;
  const size_t dir = 0x18;
  const size_t exec = 0x100, fmt = 0x120, imports = 0x140, pe_name = 0x200, sec = 0x300;
  const uint32_t pe_data_offset = 0x1000;
  x.U32(0x00, 0x58455832);  // XEX2
  x.U32(0x04, 0x00000001);  // title module
  x.U32(0x08, pe_data_offset);
  x.U32(0x10, uint32_t(sec));
  x.U32(0x14, kHeaderCount);
  size_t h = dir;
  auto header = [&](uint32_t key, uint32_t value) {
    x.U32(h, key);
    x.U32(h + 4, value);
    h += 8;
  };
  header(fhcore::xex::kExecutionInfo, uint32_t(exec));
  header(fhcore::xex::kEntryPoint, 0x82ABCDEF);
  header(fhcore::xex::kImageBaseAddress, 0x82000000);
  header(fhcore::xex::kFileFormatInfo, uint32_t(fmt));
  header(fhcore::xex::kImportLibraries, uint32_t(imports));
  header(fhcore::xex::kOriginalPeName, uint32_t(pe_name));
  header(fhcore::xex::kDefaultStackSize, 0x40000);

  // Execution info.
  x.U32(exec + 0, 0x1234ABCD);   // media id
  x.U32(exec + 4, 0x20001300);   // version 2.0.19.0
  x.U32(exec + 8, 0x20000000);   // base version
  x.U32(exec + 12, 0x4D5309C9);  // title id
  x.U8(exec + 18, 1);            // disc 1
  x.U8(exec + 19, 2);            // of 2

  // File format: size, encryption normal (1), compression normal (2).
  x.U32(fmt + 0, 8);
  x.U16(fmt + 4, 1);
  x.U16(fmt + 6, 2);

  // Import libraries: string table with two padded names, two libraries.
  const std::string names[] = {"xboxkrnl.exe", "xam.xex"};
  size_t table = imports + 12;
  size_t at = 0;
  for (const auto& name : names) {
    x.Str(table + at, name);
    at += name.size() + 1;
    if (at % 4) at += 4 - at % 4;
  }
  const uint32_t string_table_size = uint32_t(at);
  x.U32(imports + 4, string_table_size);
  x.U32(imports + 8, 2);
  size_t library = table + string_table_size;
  const uint16_t counts[] = {300, 120};
  for (int i = 0; i < 2; ++i) {
    const uint32_t library_size = 0x28 + uint32_t(counts[i] % 4) * 4;  // arbitrary
    x.U32(library + 0x00, library_size);
    x.U32(library + 0x18, 0x1000 + i);       // id
    x.U32(library + 0x1C, 0x20044E00 + i);   // version 2.0.1102.i
    x.U32(library + 0x20, 0x20000000);      // min version
    x.U16(library + 0x24, uint16_t(i));
    x.U16(library + 0x26, counts[i]);
    library += library_size;
  }
  x.U32(imports + 0, uint32_t(library - imports));

  // Original PE name.
  x.U32(pe_name, 4 + 12);
  x.Str(pe_name + 4, "game.pe");

  // Security info.
  x.U32(sec + 0x000, 0x184);
  x.U32(sec + 0x004, 0x01400000);
  x.U32(sec + 0x110, 0x82000000);
  x.U32(sec + 0x178, 0x000000FF);  // NTSC-U
  x.U32(sec + 0x180, 3);
  x.Pad(pe_data_offset);
  return x;
}

void TestXexParses() {
  Be x = MakeXex();
  const auto result = fhcore::xex::Parse(x.bytes.data(), x.size());
  CHECK(result.ok);
  if (!result.ok) {
    std::fprintf(stderr, "error: %s\n", result.error.c_str());
    return;
  }
  const auto& info = result.info;
  CHECK(info.is_title());
  CHECK(!info.is_patch());
  CHECK(info.execution.has_value());
  CHECK_EQ(info.execution->title_id, 0x4D5309C9u);
  CHECK_EQ(info.execution->media_id, 0x1234ABCDu);
  CHECK_EQ(info.execution->version.ToString(), std::string("2.0.19.0"));
  CHECK_EQ(int(info.execution->disc_number), 1);
  CHECK_EQ(int(info.execution->disc_count), 2);
  CHECK_EQ(*info.entry_point, 0x82ABCDEFu);
  CHECK_EQ(*info.image_base, 0x82000000u);
  CHECK(*info.encryption == fhcore::xex::Encryption::kNormal);
  CHECK(*info.compression == fhcore::xex::Compression::kNormal);
  CHECK_EQ(info.original_pe_name, std::string("game.pe"));
  CHECK_EQ(info.imports.size(), size_t(2));
  if (info.imports.size() == 2) {
    CHECK_EQ(info.imports[0].name, std::string("xboxkrnl.exe"));
    CHECK_EQ(info.imports[0].record_count, 300u);
    CHECK_EQ(info.imports[1].name, std::string("xam.xex"));
    CHECK_EQ(info.imports[1].record_count, 120u);
    CHECK_EQ(info.imports[1].version.ToString(), std::string("2.0.1102.1"));
  }
  CHECK(info.security.has_value());
  CHECK_EQ(info.security->load_address, 0x82000000u);
  CHECK_EQ(fhcore::xex::RegionsToString(info.security->game_regions), std::string("NTSC-U"));
  const std::string json = fhcore::xex::ToJson(result);
  CHECK(json.find("\"title_id\":\"4D5309C9\"") != std::string::npos);
  CHECK(json.find("\"name\":\"xam.xex\"") != std::string::npos);
}

void TestXexErrors() {
  const uint8_t junk[0x40] = {'M', 'Z'};
  auto r = fhcore::xex::Parse(junk, sizeof(junk));
  CHECK(!r.ok);
  CHECK(r.error.find("not an XEX2") != std::string::npos);
  r = fhcore::xex::Parse(junk, 4);
  CHECK(!r.ok);
  Be x = MakeXex();
  r = fhcore::xex::Parse(x.bytes.data(), 0x200);  // shorter than pe_data_offset
  CHECK(!r.ok);
  CHECK(r.error.find("need 4096 header bytes") != std::string::npos);
  // A file on disk round-trips through ParseFile.
  const auto path = std::filesystem::temp_directory_path() / "fhcore-test.xex";
  {
    std::ofstream out(path, std::ios::binary);
    out.write(reinterpret_cast<const char*>(x.bytes.data()), std::streamsize(x.size()));
    out.write("PEDATA", 6);
  }
  r = fhcore::xex::ParseFile(path.string());
  CHECK(r.ok);
  std::filesystem::remove(path);
}

void TestJsonQuote() {
  CHECK_EQ(fhcore::json::Quote("a\"b\\c\n"), std::string("\"a\\\"b\\\\c\\n\""));
  CHECK_EQ(fhcore::json::Quote(std::string("\x01", 1)), std::string("\"\\u0001\""));
}

uint16_t HostMachine() {
#if defined(__x86_64__)
  return fhcore::elf::kMachineX86_64;
#elif defined(__aarch64__)
  return fhcore::elf::kMachineAArch64;
#else
  return 0;
#endif
}

void TestModuleDirectory(const std::string& directory) {
  const std::set<std::string> interesting = {"SDL_main"};
  const auto main_lib = fhcore::elf::Inspect(directory + "/libfhtest_main.so", interesting);
  CHECK(main_lib.ok);
  CHECK_EQ(main_lib.soname, std::string("libfhtest_main.so"));
  CHECK(main_lib.exports.count("SDL_main") == 1);
  bool needs_dep = false;
  for (const auto& name : main_lib.needed) needs_dep |= name == "libfhtest_dep.so";
  CHECK(needs_dep);
  CHECK(main_lib.dynamic_symbol_count > 0);

  const std::string json =
      fhcore::InspectModuleDirectory(directory, "libfhtest_main.so", HostMachine());
  const size_t dep = json.find("\"load_order\":[\"libfhtest_dep.so\",\"libfhtest_main.so\"]");
  CHECK(dep != std::string::npos);
  if (dep == std::string::npos) std::fprintf(stderr, "%s\n", json.c_str());
  CHECK(json.find("\"libfhtest_side.so\"") != std::string::npos);  // side library listed
  const auto side = fhcore::elf::Inspect(directory + "/libfhtest_side.so", {});
  CHECK_EQ(side.sdl_version, std::string("3.5.0"));
  CHECK_EQ(main_lib.sdl_version, std::string(""));

  // Wrong architecture is reported for the device.
  if (HostMachine() != fhcore::elf::kMachineAArch64) {
    const std::string device =
        fhcore::InspectModuleDirectory(directory, "libfhtest_main.so", fhcore::elf::kMachineAArch64);
    CHECK(device.find("the device needs aarch64") != std::string::npos);
  }
}

void TestPlanErrors() {
  using fhcore::elf::Library;
  Library main;
  main.file_name = "libmain.so";
  main.ok = true;
  main.machine = fhcore::elf::kMachineAArch64;
  main.min_load_align = 0x10000;
  main.needed = {"librexruntime.so", "libvulkan.so", "libmissing.so"};
  Library runtime = main;
  runtime.file_name = "librexruntime.so";
  runtime.needed = {"libc++_shared.so", "liblog.so"};
  Library cxx = main;
  cxx.file_name = "libc++_shared.so";
  cxx.needed = {"libc.so"};
  cxx.min_load_align = 0x1000;
  const auto plan = fhcore::elf::PlanModule({main, runtime, cxx}, "libmain.so",
                                            fhcore::elf::kMachineAArch64);
  CHECK(!plan.ok);
  bool missing = false, no_main = false;
  for (const auto& e : plan.errors) {
    missing |= e.find("libmissing.so") != std::string::npos;
    no_main |= e.find("SDL_main") != std::string::npos;
  }
  CHECK(missing);
  CHECK(no_main);
  CHECK_EQ(plan.load_order.size(), size_t(3));
  if (plan.load_order.size() == 3) {
    CHECK_EQ(plan.load_order[0], std::string("libc++_shared.so"));
    CHECK_EQ(plan.load_order[1], std::string("librexruntime.so"));
    CHECK_EQ(plan.load_order[2], std::string("libmain.so"));
  }
  CHECK_EQ(plan.warnings.size(), size_t(1));  // 4 KiB alignment of libc++_shared
}

}  // namespace

int main(int argc, char** argv) {
  TestJsonQuote();
  TestXexParses();
  TestXexErrors();
  TestPlanErrors();
  if (argc >= 2) TestModuleDirectory(argv[1]);
  std::printf("%d checks, %d failures\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}

// FHorizon Mobile - XEX2 (Xbox 360 executable) header inspector.
//
// Reads only the plaintext part of an XEX2 file: the file header, the
// optional-header directory and the security info. The PE image that follows
// is usually encrypted and compressed; it is never decrypted here. This is
// enough to identify the title, its version and media ID, the code's load
// address and entry point, and which system libraries (xboxkrnl.exe, xam.xex,
// ...) the game imports, with how many import records each.
//
// SPDX-License-Identifier: BSD-3-Clause
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace fhcore::xex {

// Optional header keys (low byte encodes how the value is stored).
enum : uint32_t {
  kResourceInfo = 0x000002FF,
  kFileFormatInfo = 0x000003FF,
  kDeltaPatchDescriptor = 0x000005FF,
  kBaseReference = 0x00000405,
  kBoundingPath = 0x000080FF,
  kDeviceId = 0x00008105,
  kOriginalBaseAddress = 0x00010001,
  kEntryPoint = 0x00010100,
  kImageBaseAddress = 0x00010201,
  kImportLibraries = 0x000103FF,
  kChecksumTimestamp = 0x00018002,
  kEnabledForCallcap = 0x00018102,
  kEnabledForFastcap = 0x00018200,
  kOriginalPeName = 0x000183FF,
  kStaticLibraries = 0x000200FF,
  kTlsInfo = 0x00020104,
  kDefaultStackSize = 0x00020200,
  kDefaultFilesystemCacheSize = 0x00020301,
  kDefaultHeapSize = 0x00020401,
  kPageHeapSizeAndFlags = 0x00028002,
  kSystemFlags = 0x00030000,
  kExecutionInfo = 0x00040006,
  kTitleWorkspaceSize = 0x00040201,
  kGameRatings = 0x00040310,
  kLanKey = 0x00040404,
  kXbox360Logo = 0x000405FF,
  kMultidiscMediaIds = 0x000406FF,
  kAlternateTitleIds = 0x000407FF,
  kAdditionalTitleMemory = 0x00040801,
  kExportsByName = 0x00E10402,
};

struct Version {
  uint32_t raw = 0;
  uint32_t major() const { return raw >> 28; }
  uint32_t minor() const { return (raw >> 24) & 0xF; }
  uint32_t build() const { return (raw >> 8) & 0xFFFF; }
  uint32_t qfe() const { return raw & 0xFF; }
  std::string ToString() const;
};

struct ExecutionInfo {
  uint32_t media_id = 0;
  Version version;
  Version base_version;
  uint32_t title_id = 0;
  uint8_t platform = 0;
  uint8_t executable_table = 0;
  uint8_t disc_number = 0;
  uint8_t disc_count = 0;
  uint32_t savegame_id = 0;
};

struct ImportLibrary {
  std::string name;
  uint32_t id = 0;
  Version version;
  Version min_version;
  uint32_t record_count = 0;
};

struct StaticLibrary {
  std::string name;  // e.g. "XAPILIB", "D3DX9", "XMEDIA"
  uint16_t major = 0, minor = 0, build = 0;
  uint8_t qfe = 0;
  uint8_t approval = 0;
};

struct OptionalHeader {
  uint32_t key = 0;
  uint32_t value = 0;  // inline value or file offset of the data
};

struct SecurityInfo {
  uint32_t header_size = 0;
  uint32_t image_size = 0;
  uint32_t image_flags = 0;
  uint32_t load_address = 0;
  uint32_t import_table_count = 0;
  uint32_t export_table = 0;
  uint32_t game_regions = 0;
  uint32_t allowed_media_types = 0;
  uint32_t page_descriptor_count = 0;
};

enum class Encryption : uint16_t { kNone = 0, kNormal = 1 };
enum class Compression : uint16_t { kNone = 0, kBasic = 1, kNormal = 2, kDelta = 3 };

struct Info {
  uint32_t module_flags = 0;
  uint32_t pe_data_offset = 0;
  uint32_t security_offset = 0;
  std::vector<OptionalHeader> headers;

  std::optional<ExecutionInfo> execution;
  std::optional<uint32_t> entry_point;
  std::optional<uint32_t> image_base;
  std::optional<uint32_t> original_base;
  std::optional<uint32_t> default_stack_size;
  std::optional<uint32_t> default_heap_size;
  std::optional<uint32_t> system_flags;
  std::optional<uint32_t> title_workspace_size;
  std::optional<Encryption> encryption;
  std::optional<Compression> compression;
  std::string original_pe_name;
  std::vector<ImportLibrary> imports;
  std::vector<StaticLibrary> static_libraries;
  std::optional<SecurityInfo> security;

  bool is_patch() const { return (module_flags & 0x30) != 0; }  // XEX patch / delta
  bool is_title() const { return (module_flags & 0x01) != 0; }
  bool is_dll() const { return (module_flags & 0x08) != 0; }
};

struct Result {
  bool ok = false;
  std::string error;  // set when !ok
  Info info;
  std::vector<std::string> warnings;
};

// Parses XEX2 headers from the first bytes of a file. `data` should hold at
// least the whole header (pe_data_offset bytes); a shorter buffer yields an
// error that names how many bytes are needed.
Result Parse(const uint8_t* data, size_t size);

// Reads the header part of a file from disk and parses it.
Result ParseFile(const std::string& path);

// Human-readable names.
std::string RegionsToString(uint32_t regions);
std::string HexU32(uint32_t value);
std::string KeyName(uint32_t key);

// The whole result as a JSON object (stable field names, for the launcher).
std::string ToJson(const Result& result);

}  // namespace fhcore::xex

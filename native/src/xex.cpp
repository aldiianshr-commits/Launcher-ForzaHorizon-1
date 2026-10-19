// SPDX-License-Identifier: BSD-3-Clause
#include "fhcore/xex.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>

#include "fhcore/json.h"

namespace fhcore::xex {

namespace {

constexpr uint32_t kMagicXex2 = 0x58455832;  // "XEX2"
constexpr uint32_t kMagicXex1 = 0x58455831;  // "XEX1" (pre-release kits)
constexpr size_t kFileHeaderSize = 0x18;
// Headers past this size are not plausible for a retail title; it guards
// against reading a whole multi-gigabyte non-XEX file into memory.
constexpr uint32_t kMaxHeaderSize = 16u * 1024 * 1024;

class Reader {
 public:
  Reader(const uint8_t* data, size_t size) : data_(data), size_(size) {}
  bool Has(size_t offset, size_t length) const {
    return offset <= size_ && length <= size_ - offset;
  }
  uint8_t U8(size_t offset) const { return data_[offset]; }
  uint16_t U16(size_t offset) const {
    return static_cast<uint16_t>((data_[offset] << 8) | data_[offset + 1]);
  }
  uint32_t U32(size_t offset) const {
    return (uint32_t(data_[offset]) << 24) | (uint32_t(data_[offset + 1]) << 16) |
           (uint32_t(data_[offset + 2]) << 8) | uint32_t(data_[offset + 3]);
  }
  std::string CString(size_t offset, size_t max_length) const {
    std::string out;
    for (size_t i = 0; i < max_length && offset + i < size_; ++i) {
      const char c = static_cast<char>(data_[offset + i]);
      if (c == '\0') break;
      out.push_back(c);
    }
    return out;
  }
  size_t size() const { return size_; }

 private:
  const uint8_t* data_;
  size_t size_;
};

// Where an optional header's data lives and how large it is, or false when
// the header is inline (key low byte 0 or 1) or out of bounds.
bool HeaderData(const Reader& r, const OptionalHeader& header, size_t& offset, size_t& length) {
  const uint32_t low = header.key & 0xFF;
  if (low == 0x00 || low == 0x01) return false;
  offset = header.value;
  if (low == 0xFF) {
    if (!r.Has(offset, 4)) return false;
    length = r.U32(offset);
  } else {
    length = size_t(low) * 4;
  }
  return r.Has(offset, length);
}

void ParseImports(const Reader& r, size_t offset, size_t length, Info& info,
                  std::vector<std::string>& warnings) {
  // u32 size, string table { u32 size, u32 count, chars }, libraries...
  if (length < 12) {
    warnings.push_back("import library header too short");
    return;
  }
  const size_t end = offset + length;
  const uint32_t string_table_size = r.U32(offset + 4);
  const uint32_t string_count = r.U32(offset + 8);
  const size_t strings_begin = offset + 12;
  if (string_table_size > length - 12 || string_count > 4096) {
    warnings.push_back("import string table out of bounds");
    return;
  }
  std::vector<std::string> names;
  size_t at = 0;
  for (uint32_t i = 0; i < string_count && at < string_table_size; ++i) {
    std::string name = r.CString(strings_begin + at, string_table_size - at);
    at += name.size() + 1;
    if (at % 4) at += 4 - (at % 4);  // names are padded to 4 bytes
    names.push_back(std::move(name));
  }
  size_t library = strings_begin + string_table_size;
  while (library + 0x28 <= end) {
    const uint32_t library_size = r.U32(library);
    if (library_size < 0x28 || library_size > end - library) {
      warnings.push_back("import library entry out of bounds");
      break;
    }
    ImportLibrary import;
    // size(4) digest(0x14) id(4) version(4) version_min(4) name_index(2) count(2)
    import.id = r.U32(library + 0x18);
    import.version.raw = r.U32(library + 0x1C);
    import.min_version.raw = r.U32(library + 0x20);
    const uint16_t name_index = r.U16(library + 0x24);
    import.record_count = r.U16(library + 0x26);
    import.name = name_index < names.size() ? names[name_index] : "<unnamed>";
    info.imports.push_back(std::move(import));
    library += library_size;
  }
}

void ParseStaticLibraries(const Reader& r, size_t offset, size_t length, Info& info) {
  // u32 size, then 16-byte entries: name[8], major, minor, build (u16),
  // approval (u8), qfe (u8).
  for (size_t at = offset + 4; at + 16 <= offset + length; at += 16) {
    StaticLibrary library;
    library.name = r.CString(at, 8);
    library.major = r.U16(at + 8);
    library.minor = r.U16(at + 10);
    library.build = r.U16(at + 12);
    library.approval = r.U8(at + 14);
    library.qfe = r.U8(at + 15);
    info.static_libraries.push_back(std::move(library));
  }
}

}  // namespace

std::string Version::ToString() const {
  char buffer[48];
  std::snprintf(buffer, sizeof(buffer), "%u.%u.%u.%u", major(), minor(), build(), qfe());
  return buffer;
}

std::string HexU32(uint32_t value) {
  char buffer[16];
  std::snprintf(buffer, sizeof(buffer), "%08X", value);
  return buffer;
}

std::string RegionsToString(uint32_t regions) {
  if (regions == 0xFFFFFFFF) return "region free";
  std::string out;
  auto add = [&](const char* name) {
    if (!out.empty()) out += ", ";
    out += name;
  };
  if (regions & 0x000000FF) add("NTSC-U");
  if (regions & 0x00000100) add("NTSC-J Japan");
  if (regions & 0x00000200) add("NTSC-J China");
  if (regions & 0x0000FC00) add("NTSC-J Asia");
  if (regions & 0x00010000) add("PAL Australia/NZ");
  if (regions & 0x00FE0000) add("PAL Europe");
  if (regions & 0xFF000000) add("other");
  return out.empty() ? "none" : out;
}

std::string KeyName(uint32_t key) {
  switch (key) {
    case kResourceInfo: return "resource_info";
    case kFileFormatInfo: return "file_format_info";
    case kDeltaPatchDescriptor: return "delta_patch_descriptor";
    case kBaseReference: return "base_reference";
    case kBoundingPath: return "bounding_path";
    case kDeviceId: return "device_id";
    case kOriginalBaseAddress: return "original_base_address";
    case kEntryPoint: return "entry_point";
    case kImageBaseAddress: return "image_base_address";
    case kImportLibraries: return "import_libraries";
    case kChecksumTimestamp: return "checksum_timestamp";
    case kEnabledForCallcap: return "enabled_for_callcap";
    case kEnabledForFastcap: return "enabled_for_fastcap";
    case kOriginalPeName: return "original_pe_name";
    case kStaticLibraries: return "static_libraries";
    case kTlsInfo: return "tls_info";
    case kDefaultStackSize: return "default_stack_size";
    case kDefaultFilesystemCacheSize: return "default_filesystem_cache_size";
    case kDefaultHeapSize: return "default_heap_size";
    case kPageHeapSizeAndFlags: return "page_heap_size_and_flags";
    case kSystemFlags: return "system_flags";
    case kExecutionInfo: return "execution_info";
    case kTitleWorkspaceSize: return "title_workspace_size";
    case kGameRatings: return "game_ratings";
    case kLanKey: return "lan_key";
    case kXbox360Logo: return "xbox360_logo";
    case kMultidiscMediaIds: return "multidisc_media_ids";
    case kAlternateTitleIds: return "alternate_title_ids";
    case kAdditionalTitleMemory: return "additional_title_memory";
    case kExportsByName: return "exports_by_name";
    default: return "unknown";
  }
}

Result Parse(const uint8_t* data, size_t size) {
  Result result;
  Reader r(data, size);
  if (!r.Has(0, kFileHeaderSize)) {
    result.error = "file is shorter than an XEX header";
    return result;
  }
  const uint32_t magic = r.U32(0);
  if (magic == kMagicXex1) {
    result.error = "XEX1 (development kit) executables are not supported";
    return result;
  }
  if (magic != kMagicXex2) {
    result.error = "not an XEX2 executable (bad magic " + HexU32(magic) + ")";
    return result;
  }
  Info& info = result.info;
  info.module_flags = r.U32(0x04);
  info.pe_data_offset = r.U32(0x08);
  info.security_offset = r.U32(0x10);
  const uint32_t header_count = r.U32(0x14);
  if (info.pe_data_offset > kMaxHeaderSize) {
    result.error = "implausible header size " + std::to_string(info.pe_data_offset);
    return result;
  }
  if (size < info.pe_data_offset) {
    result.error = "need " + std::to_string(info.pe_data_offset) + " header bytes, have " +
                   std::to_string(size);
    return result;
  }
  if (header_count > 256 || !r.Has(kFileHeaderSize, size_t(header_count) * 8)) {
    result.error = "optional header directory out of bounds";
    return result;
  }
  for (uint32_t i = 0; i < header_count; ++i) {
    const size_t at = kFileHeaderSize + size_t(i) * 8;
    info.headers.push_back({r.U32(at), r.U32(at + 4)});
  }

  for (const OptionalHeader& header : info.headers) {
    size_t offset = 0, length = 0;
    const bool has_data = HeaderData(r, header, offset, length);
    switch (header.key) {
      case kEntryPoint: info.entry_point = header.value; break;
      case kImageBaseAddress: info.image_base = header.value; break;
      case kOriginalBaseAddress: info.original_base = header.value; break;
      case kDefaultStackSize: info.default_stack_size = header.value; break;
      case kDefaultHeapSize: info.default_heap_size = header.value; break;
      case kSystemFlags: info.system_flags = header.value; break;
      case kTitleWorkspaceSize: info.title_workspace_size = header.value; break;
      case kExecutionInfo:
        if (has_data && length >= 0x18) {
          ExecutionInfo e;
          e.media_id = r.U32(offset);
          e.version.raw = r.U32(offset + 4);
          e.base_version.raw = r.U32(offset + 8);
          e.title_id = r.U32(offset + 12);
          e.platform = r.U8(offset + 16);
          e.executable_table = r.U8(offset + 17);
          e.disc_number = r.U8(offset + 18);
          e.disc_count = r.U8(offset + 19);
          e.savegame_id = r.U32(offset + 20);
          info.execution = e;
        } else {
          result.warnings.push_back("execution info out of bounds");
        }
        break;
      case kFileFormatInfo:
        if (has_data && length >= 8) {
          info.encryption = static_cast<Encryption>(r.U16(offset + 4));
          info.compression = static_cast<Compression>(r.U16(offset + 6));
        }
        break;
      case kOriginalPeName:
        if (has_data && length > 4) info.original_pe_name = r.CString(offset + 4, length - 4);
        break;
      case kImportLibraries:
        if (has_data) ParseImports(r, offset, length, info, result.warnings);
        break;
      case kStaticLibraries:
        if (has_data) ParseStaticLibraries(r, offset, length, info);
        break;
      default:
        break;
    }
  }

  // Security info: header_size, image_size, rsa signature (0x100), unknown,
  // image_flags, load_address, section digest (0x14), import table count,
  // import digest (0x14), media id (0x10), aes key (0x10), export table,
  // header digest (0x14), regions, allowed media, page descriptor count.
  const size_t s = info.security_offset;
  if (r.Has(s, 0x184)) {
    SecurityInfo security;
    security.header_size = r.U32(s + 0x000);
    security.image_size = r.U32(s + 0x004);
    security.image_flags = r.U32(s + 0x10C);
    security.load_address = r.U32(s + 0x110);
    security.import_table_count = r.U32(s + 0x128);
    security.export_table = r.U32(s + 0x160);
    security.game_regions = r.U32(s + 0x178);
    security.allowed_media_types = r.U32(s + 0x17C);
    security.page_descriptor_count = r.U32(s + 0x180);
    info.security = security;
  } else {
    result.warnings.push_back("security info out of bounds");
  }

  if (!info.execution) result.warnings.push_back("no execution info (title ID unknown)");
  if (!info.entry_point) result.warnings.push_back("no entry point header");
  result.ok = true;
  return result;
}

Result ParseFile(const std::string& path) {
  std::ifstream file(path, std::ios::binary);
  if (!file) {
    Result result;
    result.error = "cannot open " + path;
    return result;
  }
  std::vector<uint8_t> data(kFileHeaderSize);
  file.read(reinterpret_cast<char*>(data.data()), std::streamsize(data.size()));
  if (file.gcount() < std::streamsize(kFileHeaderSize)) {
    return Parse(data.data(), size_t(file.gcount()));
  }
  // The header length is known from the first 0x18 bytes; read all of it.
  uint32_t header_size = (uint32_t(data[8]) << 24) | (uint32_t(data[9]) << 16) |
                         (uint32_t(data[10]) << 8) | uint32_t(data[11]);
  if (header_size > kMaxHeaderSize) header_size = kFileHeaderSize;
  const size_t wanted = std::max<size_t>(header_size, kFileHeaderSize);
  data.resize(wanted);
  if (wanted > kFileHeaderSize) {
    file.read(reinterpret_cast<char*>(data.data() + kFileHeaderSize),
              std::streamsize(wanted - kFileHeaderSize));
    data.resize(kFileHeaderSize + size_t(file.gcount()));
  }
  return Parse(data.data(), data.size());
}

std::string ToJson(const Result& result) {
  json::ObjectWriter out;
  out.Bool("ok", result.ok);
  if (!result.ok) {
    out.String("error", result.error);
    return out.Finish();
  }
  const Info& info = result.info;
  out.String("module_flags", HexU32(info.module_flags));
  out.Bool("is_title", info.is_title());
  out.Bool("is_patch", info.is_patch());
  out.Unsigned("header_size", info.pe_data_offset);
  if (info.execution) {
    const ExecutionInfo& e = *info.execution;
    json::ObjectWriter exec;
    exec.String("title_id", HexU32(e.title_id))
        .String("media_id", HexU32(e.media_id))
        .String("version", e.version.ToString())
        .String("base_version", e.base_version.ToString())
        .Unsigned("disc_number", e.disc_number)
        .Unsigned("disc_count", e.disc_count)
        .Unsigned("platform", e.platform)
        .String("savegame_id", HexU32(e.savegame_id));
    out.Raw("execution", exec.Finish());
  }
  if (info.entry_point) out.String("entry_point", HexU32(*info.entry_point));
  if (info.image_base) out.String("image_base", HexU32(*info.image_base));
  if (info.default_stack_size) out.Unsigned("default_stack_size", *info.default_stack_size);
  if (info.default_heap_size) out.Unsigned("default_heap_size", *info.default_heap_size);
  if (info.system_flags) out.String("system_flags", HexU32(*info.system_flags));
  if (info.encryption) {
    out.String("encryption", *info.encryption == Encryption::kNone ? "none" : "normal");
  }
  if (info.compression) {
    const char* names[] = {"none", "basic", "normal (LZX)", "delta"};
    const auto index = static_cast<uint16_t>(*info.compression);
    out.String("compression", index < 4 ? names[index] : "unknown");
  }
  if (!info.original_pe_name.empty()) out.String("original_pe_name", info.original_pe_name);
  json::ArrayWriter imports;
  for (const ImportLibrary& import : info.imports) {
    json::ObjectWriter entry;
    entry.String("name", import.name)
        .String("version", import.version.ToString())
        .String("min_version", import.min_version.ToString())
        .Unsigned("records", import.record_count);
    imports.Raw(entry.Finish());
  }
  out.Raw("imports", imports.Finish());
  json::ArrayWriter statics;
  for (const StaticLibrary& library : info.static_libraries) {
    char version[48];
    std::snprintf(version, sizeof(version), "%u.%u.%u.%u", library.major, library.minor,
                  library.build, library.qfe);
    json::ObjectWriter entry;
    entry.String("name", library.name).String("version", version);
    statics.Raw(entry.Finish());
  }
  out.Raw("static_libraries", statics.Finish());
  if (info.security) {
    const SecurityInfo& s = *info.security;
    json::ObjectWriter security;
    security.Unsigned("image_size", s.image_size)
        .String("load_address", HexU32(s.load_address))
        .String("image_flags", HexU32(s.image_flags))
        .String("regions", HexU32(s.game_regions))
        .String("regions_text", RegionsToString(s.game_regions))
        .String("allowed_media", HexU32(s.allowed_media_types))
        .Unsigned("page_descriptors", s.page_descriptor_count);
    out.Raw("security", security.Finish());
  }
  json::ArrayWriter headers;
  for (const OptionalHeader& header : info.headers) {
    json::ObjectWriter entry;
    entry.String("key", HexU32(header.key)).String("name", KeyName(header.key));
    headers.Raw(entry.Finish());
  }
  out.Raw("optional_headers", headers.Finish());
  json::ArrayWriter warnings;
  for (const std::string& warning : result.warnings) warnings.String(warning);
  out.Raw("warnings", warnings.Finish());
  return out.Finish();
}

}  // namespace fhcore::xex

// FHorizon Mobile - minimal JSON writer used by the inspectors.
// SPDX-License-Identifier: BSD-3-Clause
#pragma once

#include <cstdint>
#include <string>
#include <string_view>

namespace fhcore::json {

// A JSON string literal (quotes included) for UTF-8 text.
std::string Quote(std::string_view text);

// Appends `"key":` with a leading comma when the object already has members.
class ObjectWriter {
 public:
  ObjectWriter() : out_("{") {}
  ObjectWriter& Raw(std::string_view key, std::string_view raw_json);
  ObjectWriter& String(std::string_view key, std::string_view value);
  ObjectWriter& Number(std::string_view key, int64_t value);
  ObjectWriter& Unsigned(std::string_view key, uint64_t value);
  ObjectWriter& Double(std::string_view key, double value);
  ObjectWriter& Bool(std::string_view key, bool value);
  std::string Finish();

 private:
  void Key(std::string_view key);
  std::string out_;
  bool first_ = true;
};

class ArrayWriter {
 public:
  ArrayWriter() : out_("[") {}
  ArrayWriter& Raw(std::string_view raw_json);
  ArrayWriter& String(std::string_view value);
  std::string Finish();

 private:
  void Separator();
  std::string out_;
  bool first_ = true;
};

}  // namespace fhcore::json

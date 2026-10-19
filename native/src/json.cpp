// SPDX-License-Identifier: BSD-3-Clause
#include "fhcore/json.h"

#include <cmath>
#include <cstdio>

namespace fhcore::json {

std::string Quote(std::string_view text) {
  std::string out;
  out.reserve(text.size() + 2);
  out.push_back('"');
  for (unsigned char c : text) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      case '\b': out += "\\b"; break;
      case '\f': out += "\\f"; break;
      default:
        if (c < 0x20) {
          char buffer[8];
          std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
          out += buffer;
        } else {
          out.push_back(static_cast<char>(c));
        }
    }
  }
  out.push_back('"');
  return out;
}

void ObjectWriter::Key(std::string_view key) {
  if (!first_) out_.push_back(',');
  first_ = false;
  out_ += Quote(key);
  out_.push_back(':');
}

ObjectWriter& ObjectWriter::Raw(std::string_view key, std::string_view raw_json) {
  Key(key);
  out_ += raw_json;
  return *this;
}

ObjectWriter& ObjectWriter::String(std::string_view key, std::string_view value) {
  Key(key);
  out_ += Quote(value);
  return *this;
}

ObjectWriter& ObjectWriter::Number(std::string_view key, int64_t value) {
  Key(key);
  out_ += std::to_string(value);
  return *this;
}

ObjectWriter& ObjectWriter::Unsigned(std::string_view key, uint64_t value) {
  Key(key);
  out_ += std::to_string(value);
  return *this;
}

ObjectWriter& ObjectWriter::Double(std::string_view key, double value) {
  Key(key);
  if (!std::isfinite(value)) {
    out_ += "null";
  } else {
    char buffer[64];
    std::snprintf(buffer, sizeof(buffer), "%.6g", value);
    out_ += buffer;
  }
  return *this;
}

ObjectWriter& ObjectWriter::Bool(std::string_view key, bool value) {
  Key(key);
  out_ += value ? "true" : "false";
  return *this;
}

std::string ObjectWriter::Finish() {
  out_.push_back('}');
  return std::move(out_);
}

void ArrayWriter::Separator() {
  if (!first_) out_.push_back(',');
  first_ = false;
}

ArrayWriter& ArrayWriter::Raw(std::string_view raw_json) {
  Separator();
  out_ += raw_json;
  return *this;
}

ArrayWriter& ArrayWriter::String(std::string_view value) {
  Separator();
  out_ += Quote(value);
  return *this;
}

std::string ArrayWriter::Finish() {
  out_.push_back(']');
  return std::move(out_);
}

}  // namespace fhcore::json

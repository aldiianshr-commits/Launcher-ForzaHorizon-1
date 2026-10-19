// FHorizon Mobile - JNI entry points for the launcher (id.fhorizon.mobile.core.NativeCore).
// SPDX-License-Identifier: BSD-3-Clause
#include <jni.h>

#include <string>

#include "fhcore/elf_module.h"
#include "fhcore/module_scan.h"
#include "fhcore/vk_probe.h"
#include "fhcore/xex.h"

namespace {

std::string ToString(JNIEnv* env, jstring value) {
  if (!value) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string out = chars ? chars : "";
  if (chars) env->ReleaseStringUTFChars(value, chars);
  return out;
}

jstring ToJava(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_id_fhorizon_mobile_core_NativeCore_inspectXex(JNIEnv* env, jclass,
                                                                            jstring path) {
  return ToJava(env, fhcore::xex::ToJson(fhcore::xex::ParseFile(ToString(env, path))));
}

JNIEXPORT jstring JNICALL Java_id_fhorizon_mobile_core_NativeCore_inspectModule(JNIEnv* env, jclass,
                                                                               jstring directory,
                                                                               jstring entry) {
  return ToJava(env, fhcore::InspectModuleDirectory(ToString(env, directory), ToString(env, entry),
                                                    fhcore::elf::kMachineAArch64));
}

JNIEXPORT jstring JNICALL Java_id_fhorizon_mobile_core_NativeCore_probeVulkan(JNIEnv* env, jclass) {
  return ToJava(env, fhcore::vk::ProbeJson());
}

JNIEXPORT jstring JNICALL Java_id_fhorizon_mobile_core_NativeCore_coreVersion(JNIEnv* env, jclass) {
  return ToJava(env, FHCORE_VERSION);
}

}  // extern "C"

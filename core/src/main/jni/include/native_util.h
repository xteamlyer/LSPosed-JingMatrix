/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 - 2022 LSPosed Contributors
 */

#include <dlfcn.h>
#include <sys/mman.h>

#include "elf_util.h"
#include "symbol_cache.h"
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-value"
#pragma once

#include <context.h>

#include <cassert>

#include "../src/native_api.h"
#include "config.h"
#include "config_bridge.h"
#include "logging.h"
#include "utils/jni_helper.hpp"

namespace lspd {

[[gnu::always_inline]]
inline bool RegisterNativeMethodsInternal(JNIEnv *env, std::string_view class_name,
                                          const JNINativeMethod *methods, jint method_count) {
    auto clazz = Context::GetInstance()->FindClassFromCurrentLoader(env, class_name.data());
    if (clazz.get() == nullptr) {
        // LOGF("Couldn't find class: {}", class_name.data());
        return false;
    }
    return JNI_RegisterNatives(env, clazz, methods, method_count);
}

#if defined(__cplusplus)
#define _NATIVEHELPER_JNI_MACRO_CAST(to) reinterpret_cast<to>
#else
#define _NATIVEHELPER_JNI_MACRO_CAST(to) (to)
#endif

#ifndef LSP_NATIVE_METHOD
#define LSP_NATIVE_METHOD(className, functionName, signature)                                      \
    {#functionName, signature,                                                                     \
     _NATIVEHELPER_JNI_MACRO_CAST(void *)(                                                         \
         Java_org_lsposed_lspd_nativebridge_##className##_##functionName)}
#endif

#define JNI_START [[maybe_unused]] JNIEnv *env, [[maybe_unused]] jclass clazz

#ifndef LSP_DEF_NATIVE_METHOD
#define LSP_DEF_NATIVE_METHOD(ret, className, functionName, ...)                                   \
    extern "C" ret Java_org_lsposed_lspd_nativebridge_##className##_##functionName(JNI_START,      \
                                                                                   ##__VA_ARGS__)
#endif

#define REGISTER_LSP_NATIVE_METHODS(class_name)                                                    \
    RegisterNativeMethodsInternal(env, GetNativeBridgeSignature() + #class_name, gMethods,         \
                                  arraysize(gMethods))

inline std::string GetNativeBridgeSignature() {
    const auto &obfs_map = ConfigBridge::GetInstance()->obfuscation_map();
    static auto signature = obfs_map.at("org.lsposed.lspd.nativebridge.");
    return signature;
}

}  // namespace lspd

#pragma clang diagnostic pop

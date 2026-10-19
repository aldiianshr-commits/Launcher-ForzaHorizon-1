# Arsitektur FHorizon Mobile

## Proses

| Proses | Isi |
|---|---|
| `id.fhorizon.mobile` | Launcher (Compose): pemindaian, modul, pengaturan, kontrol, save, log. Memuat `libfhcore.so` (C++ static libc++). |
| `id.fhorizon.mobile:game` | `FHorizonGameActivity` (turunan `SDLActivity`): memuat modul backend dan menjalankan game. Tidak memuat `libfhcore.so`, sehingga satu-satunya `libc++_shared.so` di proses itu adalah milik modul. |

Proses terpisah membuat crash atau `exit()` runtime tidak menutup launcher, dan state statis
SDL selalu bersih di setiap start.

## Alur Play

1. `AppViewModel.play` → pemeriksaan tanpa status BLOCK.
2. `GameLauncher.prepare`:
   - membuat `Save/{user,config,logs,cache,drivers,crashes,update,backups}`;
   - menulis cvar dari `GraphicsSettings.toCvars()` ke `Save/config/pinyon_shift.toml`
     (backup di `Save/config/backups`), plus `pinyon_shift_touch_controls` dan
     `vulkan_capability_report`;
   - menyusun environment: `HOME`, `PINYON_SHIFT_STATE_ROOT`, `PINYON_SHIFT_GAME_ROOT`,
     `REX_ANDROID_DRIVERS_DIR`, `PINYON_SHIFT_BUILD_MANIFEST`, `PINYON_SHIFT_ADRENO_MODEL`;
   - ukuran swapchain dan refresh rate;
   - menulis `files/launch-plan.json`.
3. `FHorizonGameActivity.onCreate` membaca rencana, `Os.setenv`, mengonfigurasi
   `FHVirtualPad`, lalu `SDLActivity.onCreate`:
   - `loadLibraries()` → cek fitur CPU (`atomics`, `asimdrdm`) → `System.load` tiap
     pustaka dalam `load_order` (dependensi lebih dulu; linker Android menyelesaikan
     DT_NEEDED berikutnya dengan soname yang sudah dimuat);
   - SDL membandingkan versi Java (3.5.0) dengan versi C;
   - `getMainSharedObject()` → `files/modules/active/libmain.so`, SDL memanggil `SDL_main`.
4. Runtime menemukan plugin GPU dan hook adrenotools di folder `librexruntime.so`
   (`dladdr`), yaitu folder modul.

## Modul backend

```
files/modules/active/
  libc++_shared.so  librexruntime.so  librexgpu-fh1.so  libmain.so
  libpinyon_shift_SpeechFacade_default.so  libpinyon_shift_XMediaFacade_default.so
  libhook_impl.so  libmain_hook.so  libfile_redirect_hook.so  libgsl_alloc_hook.so
  [libamd_fidelityfx_vk.so]
  meta/pinyon_shift_build.json  meta/module.json
  installed.json      ← urutan muat, versi SDL, commit, SHA-256 libmain
```

Validasi saat impor (`ModuleManager` + `native/src/elf_module.cpp`): ELF64 LE, AArch64,
ET_DYN, alignment PT_LOAD, DT_NEEDED terpenuhi oleh modul atau pustaka sistem NDK, ekspor
`SDL_main`, plugin GPU ada, string revisi SDL cocok, platform build `android-arm64`.
Tidak ada kode yang dieksekusi selama impor.

## Input

- **Sentuh:** `TouchOverlayView` menggambar kontrol; `dispatchTouchEvent` aktivitas
  memberinya setiap event. Gestur yang dimulai di kontrol dikonsumsi; lainnya diteruskan
  ke surface SDL (menu sentuh Pinyon Shift tetap bisa dipakai).
  Output → `FHVirtualPad` → `SDLControllerManager.nativeAddJoystick/onNativePadDown/onNativeJoy`.
  Pad virtual diumumkan saat SDL memindai controller (patch kecil di
  `SDLControllerManager.pollInputDevices`, ditandai "FHorizon Mobile patch").
- **Gamepad fisik:** `dispatchKeyEvent` menulis ulang key code sesuai pemetaan;
  `dispatchGenericMotionEvent` menerapkan dead zone, sensitivitas, tukar trigger, balik Y.
- **Getar:** patch di `hapticRun/hapticRumble/hapticStop`: pad virtual → vibrator tablet;
  getar controller bisa dimatikan.

## Native core (`native/`)

| File | Fungsi |
|---|---|
| `xex.cpp` | Parser header XEX2 (big-endian): optional headers, execution info, file format, import library (string table ber-padding 4), static libraries, security info |
| `elf_module.cpp` | Parser ELF64 (program header, dynamic section, dynsym via section header / DT_HASH / DT_GNU_HASH), deteksi revisi SDL, perencana urutan muat |
| `vk_probe.cpp` | `dlopen("libvulkan.so")`, enumerasi perangkat, fitur wajib/fallback renderer |
| `jni_bridge.cpp` | `NativeCore.inspectXex/inspectModule/probeVulkan` |
| `tools/fhinspect.cpp` | CLI untuk PC |

# FHorizon Mobile

Launcher Android native (ARM64) untuk menjalankan **Forza Horizon (Xbox 360, 2012)** melalui
**static recompilation PowerPC → C++ → ARM64**, bukan emulator Xbox 360. Target utama:
**Xiaomi Pad 6** (Snapdragon 870, Adreno 650).

> **Status jujur (Oktober 2026):** launcher, pemuat backend, pemindaian file, pengaturan,
> kontrol dan save sudah dibangun dan diuji otomatis di CI. **Forza Horizon belum pernah
> dijalankan oleh proyek ini di perangkat mana pun**, karena kode game hasil rekompilasi
> harus dibangun dari disc milik Anda sendiri dan tidak bisa ada di repository publik.
> Backend yang dipakai (Pinyon Shift + ShiftGlue/ReXGlue) sudah berjalan di Android
> (alpha) pada Snapdragon 8 Gen 2 / 8 Elite menurut pengembangnya; **Snapdragon 870 /
> Adreno 650 belum pernah diuji siapa pun.** Lihat [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Cara kerjanya

```
 PC Anda (sekali)                                   Xiaomi Pad 6
 ─────────────────                                  ─────────────────────────────────────────────
 ISO disc Anda (MS-2505)                            FHorizon Mobile (APK ini, publik)
   │ Pinyon Shift setup                               ├─ Pindai /storage/emulated/0/FHorizon
   │  • verifikasi & ekstrak                          │   (2.400 file, ukuran + SHA-256)
   │  • ReXGlue/ShiftGlue codegen:                    ├─ Baca header default.xex (XEX2)
   │    PowerPC default.xex → C++                     ├─ Probe Vulkan (fitur wajib renderer)
   │ pinyon.py android build (NDK)                    ├─ Impor & validasi modul backend (ELF)
   │  • C++ → AArch64 .so                             ├─ Tulis cvar grafis → Save/config/pinyon_shift.toml
   ▼                                                  └─ Play → proses ":game"
 tools/fhorizon_module.py                                  FHorizonGameActivity (SDL3)
   → fhorizon-module-*.zip  ───── salin ─────▶              System.load: libc++_shared → librexruntime
 .local/game/base (file game) ── salin ─────▶              → facade → libmain.so (game hasil rekompilasi)
                                                           runtime: HLE kernel/XAM, Xenos→Vulkan,
                                                           XMA audio, input SDL, save di FHorizon/Save
```

Kenapa dua bagian: kode C++ hasil rekompilasi adalah turunan langsung dari `default.xex`
yang dilindungi hak cipta. Ia dibangun di PC Anda dari disc Anda, disimpan hanya di
perangkat Anda, dan tidak pernah diunggah. APK di repository ini tidak berisi kode atau
aset game apa pun.

## Riset jalur rekompilasi (ringkas)

| Proyek | Isi | Untuk FH1 di Android |
|---|---|---|
| [XenonRecomp](https://github.com/hedge-dev/XenonRecomp) | Penerjemah PPC → C++ saja; **tanpa runtime** (kernel/XAM/GPU tidak ada) | Proyek FH1 berbasis ini masih tanpa runtime grafis |
| [ReXGlue SDK](https://github.com/rexglue/rexglue-sdk) | Recompiler + runtime turunan Xenia (HLE kernel/XAM, GPU Xenos → Vulkan/D3D12, audio), build Linux arm64 | Fondasi yang tepat |
| [Pinyon Shift](https://github.com/arcanite24/pinyon-shift) (BSD-3) | Rekompilasi FH1 dengan fork ReXGlue "ShiftGlue", termasuk **port Android** (Vulkan, Turnip/adrenotools, kontrol sentuh) | **Dipakai sebagai backend** |

Detail dan alasannya: [docs/RESEARCH.md](docs/RESEARCH.md). Arsitektur launcher:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Kebutuhan

**Tablet/ponsel:** arm64 dengan CPU ARMv8.2 (Snapdragon 845 ke atas), Android 13+ (modul
dibangun dengan minSdk 33; launcher sendiri terpasang dari Android 10), driver Vulkan yang
memenuhi fitur wajib renderer (atau Mesa Turnip), RAM 8 GB+ (Pinyon Shift menargetkan
12 GB), ruang ±10 GB.

**PC untuk membangun modul (sekali):** Windows 10/11 x64 atau Linux x86-64, ±45 GB kosong,
Git, Python 3, dan ISO Forza Horizon **USA retail (MS-2505, title ID 4D5309C9)** hasil
dump sendiri. Dump lain ditolak backend.

## Instalasi

1. **APK:** unduh `FHorizonMobile-*-arm64-v8a.apk` dari tab **Actions** (artifact
   `FHorizonMobile-apk`) atau **Releases**, lalu pasang.
2. **Izin:** buka FHorizon Mobile → Beranda → *Buka pengaturan izin* → aktifkan
   **Akses semua file**. Folder dibuat otomatis:
   ```
   /storage/emulated/0/FHorizon/          ← file game (atau subfolder Game/)
   /storage/emulated/0/FHorizon/Save/     ← save, config, log, cache shader, driver
   /storage/emulated/0/FHorizon/Backups/  ← backup save
   /storage/emulated/0/FHorizon/Module/   ← taruh modul backend di sini
   /storage/emulated/0/FHorizon/Logs/     ← log launcher
   ```
3. **Bangun modul di PC:**
   ```bash
   git clone https://github.com/aldiianshr-commits/Launcher-ForzaHorizon-1
   cd Launcher-ForzaHorizon-1
   # Linux
   ./tools/build-module.sh --iso ~/forza-horizon.iso
   # Windows (PowerShell)
   powershell -ExecutionPolicy Bypass -File tools\build-module.ps1 -IsoPath D:\forza-horizon.iso
   ```
   Skrip memakai alat Pinyon Shift sendiri pada commit yang dipin (`9331e45`):
   verifikasi ISO, ekstrak, codegen ReXGlue, kompilasi NDK arm64, lalu membungkus hasilnya
   menjadi `module-out/fhorizon-module-*.zip`. Sudah punya `pinyon-shift.apk` dari
   `pinyon.py android build`? APK itu bisa diimpor langsung.
4. **Salin ke tablet** (USB/MTP): zip modul → `FHorizon/Module/`, folder
   `.work/pinyon-shift/.local/game/base` (berisi `default.xex` dan `media/`) →
   `FHorizon/Game/`.
5. **Di aplikasi:** *File game* → Pindai ulang → *Verifikasi SHA-256 penuh*;
   *Backend* → *Impor dari FHorizon/Module*.
6. **Driver GPU (disarankan untuk Adreno 650):** jika Beranda menunjukkan driver sistem
   tidak memenuhi fitur wajib, impor paket Mesa Turnip (`.zip` adrenotools yang mendukung
   Adreno 6xx) di *Grafis → Driver GPU*, lalu pilih.
7. **Beranda → Play.** Tombol hanya aktif bila tidak ada pemeriksaan merah.

## Fitur launcher

- **Pemindaian game:** cari `default.xex` di folder default/terpilih (SAF), cocokkan 2.400
  file dengan katalog dump yang didukung (ukuran cepat, SHA-256 penuh), baca header XEX2
  (title ID, media ID, versi, region, entry point, import xboxkrnl/xam), deteksi file
  hilang. Mode *salin ke penyimpanan aplikasi* jika tidak ingin memberi izin semua file.
- **Kompatibilitas:** daftar pemeriksaan (CPU, Android, RAM, Vulkan, file, modul, SDL)
  dengan status OK/peringatan/blokir dan laporan diagnostik yang bisa dibagikan.
- **Modul backend:** impor `.zip`/`.apk`, validasi ELF AArch64 (DT_NEEDED, SONAME, ekspor
  `SDL_main`, plugin GPU, alignment), deteksi versi SDL, urutan muat otomatis.
- **Grafis** (ditulis sebagai cvar backend nyata, lihat tabel di bawah).
- **Kontrol sentuh:** stik kiri (mengambang), stik kanan, pedal LT/RT analog, A/B/X/Y,
  LB/RB, Back/Start, D-pad, L3/R3; editor posisi/ukuran/transparansi per kontrol,
  transparansi global, sembunyi otomatis, getar. Dikirim ke game sebagai **gamepad virtual
  SDL**.
- **Gamepad Bluetooth/USB:** dideteksi SDL; pemetaan ulang tombol (tekan tombol fisik),
  dead zone & sensitivitas stik, dead zone trigger, tukar LT/RT, balik Y kanan, getar
  controller dan getar tablet.
- **Save:** ringkasan profil, backup zip, pulihkan (dengan backup otomatis sebelum
  pulih), impor/ekspor.
- **Log:** log game (`Save/logs`, `Save/crashes`), log launcher, argumen backend lanjutan.
- UI Material 3, dark mode mengikuti sistem, navigasi rail untuk tablet.

### Pengaturan grafis → backend

| Opsi | Cvar / mekanisme | Catatan |
|---|---|---|
| Resolusi 480p/720p/900p/1080p | ukuran swapchain (`SurfaceHolder.setFixedSize`) | Game merender 1280×720 (skala 1x); backend membatasi Android ke 1x |
| Upscaler | `present_effect` = bilinear / cas / fsr | |
| FPS 30/45/60 | `pinyon_shift_fh1_render_fps_limit`, `host_present_fps_limit=0`, `Surface.setFrameRate` 60/90/60 Hz | 45 belum diuji backend |
| Tekstur Rendah/Sedang/Tinggi | `vulkan_texture_max_size` 256/1024/0 | |
| Bayangan | `pinyon_shift_fh1_shadows` | Hanya Mati/Aktif; Rendah/Sedang dinonaktifkan di UI |
| Anti-aliasing | `fh1_msaa_single_sample`, `fh1_msaa_2x`, `swap_post_effect` | MSAA = MSAA 4x milik game (2x digabung); dicek terhadap sample count Vulkan |
| Anisotropic | `anisotropic_override` -1/0/2/3/4/5 | Dicek terhadap `maxSamplerAnisotropy` |
| V-Sync | `vsync` | |
| Shader cache | `store_shaders`, `vulkan_pipeline_cache_persist` | |
| Driver GPU | `android_gpu_driver` + `REX_ANDROID_DRIVERS_DIR` | Turnip dimuat lewat libadrenotools oleh runtime |
| Aspect Otomatis/16:9/16:10 | `present_letterbox`, `present_allow_overscan_cutoff`, `pinyon_shift_hor_plus` | 16:10 = Hor+ |
| Fullscreen | mode immersive Android | |
| Preset Very Low…Ultra | kombinasi di atas | Belum diukur di Adreno 650 |
| Optimasi Android | `pinyon_shift_fh1_env_map_rate`, `fh1_untile_predicated_tiling`, `fh1_coarse_shading`, `texture_gamma_host_srgb`, `texture_cache_memory_limit_*`, `android_thread_cores`, `android_gpu_turbo`, sustained performance mode | |

Pengaturan ditulis ke `Save/config/pinyon_shift.toml` saat Play (backup otomatis di
`Save/config/backups`). Menu SETTINGS di dalam game mengedit file yang sama.

## Build dari source

```bash
# Native core + tes host (Linux/macOS; Vulkan-Headers opsional untuk probe)
git clone --depth 1 --branch v1.3.275 https://github.com/KhronosGroup/Vulkan-Headers /tmp/vkh
cmake -S native -B build/native -G Ninja -DFHCORE_VULKAN_HEADERS=/tmp/vkh/include
cmake --build build/native && ctest --test-dir build/native --output-on-failure
python3 tools/tests/test_module_pipeline.py build/native/fhinspect

# APK (Android SDK + NDK; JDK 17)
./gradlew testDebugUnitTest assembleRelease
# hasil: app/build/outputs/apk/release/app-release.apk (arm64-v8a)
```

`fhinspect` juga berguna di PC: `fhinspect xex default.xex`, `fhinspect module <folder .so>`.

CI (`.github/workflows/android.yml`) menjalankan tes native, tes pipeline modul sintetis,
unit test Kotlin, dan membangun APK release + debug. Tanpa secret, APK release ditandatangani
dengan kunci debug runner (berubah tiap build; update mungkin perlu uninstall, data di
`FHorizon/` tetap aman). Untuk kunci tetap, isi secret `FHORIZON_KEYSTORE_BASE64`,
`FHORIZON_KEYSTORE_PASSWORD`, `FHORIZON_KEY_ALIAS`, `FHORIZON_KEY_PASSWORD`.

## Struktur repository

```
app/                      Launcher Android (Kotlin, Jetpack Compose)
  src/main/java/id/fhorizon/mobile/
    core/                 path, izin penyimpanan, log, jembatan JNI
    game/                 katalog, pemindai, XEX, rencana peluncuran, FHorizonGameActivity
    module/               impor & validasi modul backend, kompatibilitas SDL
    device/               info perangkat, laporan Vulkan
    compat/               penilaian kompatibilitas
    settings/             pengaturan grafis → cvar, editor pinyon_shift.toml
    controls/             tata letak & overlay sentuh, pemetaan gamepad
    save/  drivers/       backup save, driver GPU
    ui/                   layar Compose, editor tata letak
  src/main/java/org/libsdl/app/   SDL3 Java glue (commit 8bf3b72, zlib) + FHVirtualPad
  src/main/assets/catalog/        daftar file dump yang didukung (metadata saja)
native/                   libfhcore: inspektor XEX2, inspektor ELF/modul, probe Vulkan, fhinspect, tes
tools/                    build-module.{sh,ps1}, fhorizon_module.py, make_catalog.py, tes
docs/                     COMPATIBILITY.md, RESEARCH.md, ARCHITECTURE.md
```

## Legal

FHorizon Mobile tidak berafiliasi dengan Microsoft, Xbox, Turn 10 Studios, atau Playground
Games. Repository ini tidak berisi file game, kode game, aset, kunci, atau modul hasil
rekompilasi. Gunakan hanya dengan disc yang Anda miliki. Jangan membagikan modul backend
atau file game Anda. Lihat [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Lisensi kode proyek ini: BSD 3-Clause.

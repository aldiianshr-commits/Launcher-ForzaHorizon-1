# Laporan kompatibilitas

Terakhir diperbarui: 20 Oktober 2026 · launcher 0.1.0 · backend yang dipin: Pinyon Shift
`9331e45` + ShiftGlue SDK `79d3e6f` (SDL 3.5.0 `8bf3b72`).

## Ringkasan

| | Status |
|---|---|
| Forza Horizon dapat dimainkan di Xiaomi Pad 6 lewat FHorizon Mobile | **Belum diketahui — belum pernah dijalankan.** Tidak ada perangkat Android atau disc di lingkungan pengembangan ini, dan kode game hasil rekompilasi tidak boleh dibangun di CI publik. |
| Forza Horizon via Pinyon Shift di Android (menurut pengembang Pinyon Shift) | Alpha: berjalan dengan renderer Vulkan di Snapdragon 8 Elite (Adreno 830) dan AYN Odin 2 Portal (Adreno 740); 60 fps sampai perangkat panas lalu ±30 fps. Target mereka: Snapdragon 8 Gen 2+, RAM 12 GB, Android 13+, Vulkan 1.3. |
| Snapdragon 870 / Adreno 650 | **Tidak pernah diuji siapa pun.** Lihat risiko di bawah. |

Status ini akan diperbarui hanya setelah ada hasil nyata dari perangkat (log
`Save/logs`, laporan diagnostik dari menu Log).

## Komponen

### Sudah berfungsi dan teruji (otomatis, CI)

| Komponen | Bukti |
|---|---|
| Inspektor XEX2 (header, execution info, import library, security info, region) | `native/tests` dengan header XEX2 sintetis; kasus galat (magic salah, file terpotong) |
| Inspektor ELF + perencana urutan muat modul | Tes dengan pustaka host nyata dan pipeline modul AArch64 sintetis (`tools/tests/test_module_pipeline.py`): urutan `libc++_shared → librexruntime → facade → libmain`, plugin GPU & hook sebagai pustaka samping, deteksi SDL 3.5.0 |
| Pembungkus modul (`tools/fhorizon_module.py`) dari APK Pinyon Shift | Tes pipeline: zip modul, SHA-256 per pustaka, manifest build, driver bawaan |
| Katalog file dump (2.400 file, 7.221.319.555 byte) | Dihasilkan dari manifest Pinyon Shift; tes parsing |
| Pemindai file game (case-insensitive, ukuran, SHA-256, batal) | Unit test JVM |
| Editor `pinyon_shift.toml` (aturan sama dengan `host_config.h` Pinyon Shift) | Unit test JVM (CRLF, komentar, kutip, backup) |
| Pemetaan pengaturan grafis → cvar | Unit test: setiap cvar yang ditulis ada di ShiftGlue `79d3e6f` / Pinyon `9331e45` |
| Backup/pulihkan save (zip, perlindungan zip-slip, backup sebelum pulih) | Unit test JVM |
| Kecocokan SDL Java vendored ↔ konstanta launcher | Unit test membaca `SDLActivity.java` |
| APK arm64-v8a terbangun (Gradle + NDK/CMake) | Workflow `Build APK` |

### Diimplementasikan, belum diuji di perangkat

| Komponen | Catatan |
|---|---|
| Probe Vulkan (fitur wajib renderer ShiftGlue: independentBlend, fragment/vertexPipelineStoresAndAtomics, format depth, A2B10G10R10 storage) | Dikompilasi untuk Android; di host tanpa GPU melaporkan galat dengan benar |
| `FHorizonGameActivity`: `System.load` modul dari penyimpanan internal, `SDL_main` di `libmain.so`, environment `PINYON_SHIFT_*`/`REX_ANDROID_DRIVERS_DIR`, proses `:game` terpisah | Mekanisme sama dengan `PinyonShiftActivity` (yang memuat dari `nativeLibraryDir`); pemuatan dari `files/modules` mengikuti cara runtime memuat driver Turnip |
| Gamepad virtual SDL untuk kontrol sentuh (`FHVirtualPad`) | Memakai `nativeAddJoystick`/`onNativePadDown`/`onNativeJoy` SDL 3.5.0; mapping dibuat SDL dari mask tombol/sumbu |
| Pemetaan ulang tombol & pembentukan stik/trigger gamepad fisik | Event ditulis ulang sebelum SDL |
| Ukuran swapchain, petunjuk refresh rate, immersive, sustained performance | API Android standar |
| Impor driver Turnip (adrenotools .zip) | Format sama dengan impor di menu game Pinyon Shift |
| Seluruh UI launcher | Belum dijalankan di emulator/perangkat |

### Belum berfungsi / batasan yang diketahui

| Batasan | Sebab |
|---|---|
| Kode game tidak bisa disertakan di APK | Turunan `default.xex` berhak cipta; harus dibangun di PC pemain |
| Resolusi render internal tetap 1280×720 | ShiftGlue membatasi Android ke skala 1x (buffer resolve skala lebih tinggi tidak muat); pilihan resolusi mengatur output |
| Bayangan hanya Mati/Aktif | Backend tidak punya tingkat kualitas bayangan |
| 45 FPS | Nilai valid untuk cvar, tetapi Pinyon Shift hanya menguji 30/40/60 |
| Probe Vulkan hanya untuk driver sistem | Driver Turnip baru diperiksa runtime saat game dimulai (log `VULKAN_CAPABILITY_REPORT`) |
| Hanya disc USA MS-2505 | Codegen Pinyon Shift dibuat untuk executable itu; title update v4 butuh build terpisah |
| Audio gameplay belum lengkap, mobil pemain bisa bergetar, tabrakan kadang salah | Masalah yang diketahui di Pinyon Shift (bukan khusus Android) |
| Rekompilasi di perangkat | Codegen + kompilasi butuh ±45 GB dan toolchain PC |

## Risiko khusus Xiaomi Pad 6 (Snapdragon 870 / Adreno 650)

1. **RAM.** Pinyon Shift memperkirakan ±3 GB resident pada skala 1x dan menulis bahwa
   perangkat 8 GB "mungkin tidak" bertahan (belum diukur). Pad 6 6 GB/8 GB berisiko
   dihentikan `lmkd`. Mitigasi di launcher: preset Low/Very Low, batas cache tekstur rendah,
   tekstur maksimum 256/1024.
2. **Driver Vulkan sistem.** Fitur wajib renderer mungkin tidak dilaporkan oleh driver
   Qualcomm untuk Adreno 650; launcher memblokir Play dan menyarankan Turnip. Turnip
   (Mesa) mendukung a6xx dengan Vulkan 1.3, tetapi build Turnip yang dibundel Pinyon
   Shift (Gen8 V37) ditujukan untuk Adreno 7xx/8xx.
3. **Performa.** Di Adreno 740, preset SMOOTH 60 butuh ±15,4 ms GPU per frame. Adreno 650
   jauh lebih lemah, jadi 30 FPS adalah target realistis; 60 FPS kemungkinan tidak
   tercapai.
4. **CPU.** Kryo 585 (A77 + A55) adalah ARMv8.2 dengan LSE dan RDM, sesuai baseline
   modul. Dekoder perintah GPU berjalan di satu thread; opsi "core prime" mengikatnya ke
   cpu7 (3,2 GHz).
5. **Termal.** Pinyon Shift turun dari 60 ke ±30 fps saat kulit perangkat > 44 °C di
   Snapdragon 8 Elite. Launcher menampilkan status termal dan menawarkan sustained
   performance mode.

## Cara melaporkan hasil

Menu **Log** → *Bagikan laporan* (pemeriksaan, perangkat, Vulkan, XEX, modul, cvar) dan
log game terbaru dari `Save/logs`. Jangan sertakan file game atau modul.

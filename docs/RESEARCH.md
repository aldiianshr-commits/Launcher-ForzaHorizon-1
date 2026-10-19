# Riset: jalur static recompilation Forza Horizon (Xbox 360) ke Android ARM64

Tujuan: menjalankan Forza Horizon 1 di Android tanpa emulator, dengan menerjemahkan kode
PowerPC (Xenon) di `default.xex` menjadi C++ yang dikompilasi untuk AArch64, plus runtime
yang menggantikan sistem Xbox 360.

## Yang dibutuhkan sebuah port static recompilation

| Bagian | Isi |
|---|---|
| Format executable | XEX2: header plaintext (execution info, import, security info), image PE terenkripsi (AES) dan terkompresi (LZX) yang harus didekripsi sebelum codegen |
| Recompiler | Analisis fungsi (batas fungsi, jump table, helper save/restore register, setjmp/longjmp), terjemahan per instruksi PPC/VMX128 ke C++ |
| Kernel & XAM | HLE `xboxkrnl.exe` (thread, memori, file system, sinkronisasi, waktu) dan `xam.xex` (profil, konten/save, UI sistem, jaringan) — FH1 mengimpor ratusan ordinal |
| GPU Xenos | Command processor PM4, terjemahan shader microcode Xenos ke SPIR-V/DXIL, emulasi EDRAM, resolve, format tekstur (tile, endian) |
| Audio | Dekoder XMA, mixer XAudio |
| Input | XInput dari SDL |
| Penyimpanan | Device content/save yang dipetakan ke folder host |
| Memori | Ruang alamat tamu 4 GB + tampilan fisik 512 MB (dipetakan beberapa kali), proteksi halaman untuk deteksi tulis (MMIO, watch tekstur) |

## Kandidat

### XenonRecomp (hedge-dev)
Penerjemah PPC → C++ yang dipakai UnleashedRecomp. README-nya menyatakan proyek ini
**tidak menyediakan runtime**; MMIO tidak diimplementasikan, dan analisis jump table dibuat
untuk Sonic Unleashed. Instruksi VMX memakai intrinsik x86, dengan dukungan ARM64 lewat
SIMDe. Proyek FH1 yang memakainya (mis. *ForzaHorizonRecomp*) masih di tahap codegen tanpa
runtime grafis, audio, input atau file system.

Kesimpulan: memakai XenonRecomp berarti menulis ulang hampir seluruh runtime Xbox 360 dari
nol. Tidak realistis untuk menghasilkan game yang benar-benar berjalan.

### ReXGlue SDK
"Xbox 360 Recompilation Runtime and Toolkit", turunan Xenia (BSD-3). Berisi recompiler
(codegen PPC → C++ dengan konfigurasi TOML per game) **dan runtime**: HLE kernel/XAM dari
Xenia, GPU Xenos dengan backend Vulkan dan D3D12, audio, SDL3 untuk jendela dan input.
CI-nya membangun Linux arm64; kode sumbernya sudah punya jalur `__ANDROID__`
(`ANativeWindow`, `VK_KHR_android_surface`) dan memakai SIMDe untuk VMX di ARM.

### Pinyon Shift (arcanite24, BSD-3)
Rekompilasi Forza Horizon memakai fork ReXGlue bernama **ShiftGlue SDK**. Pinyon Shift
sudah punya:
- konfigurasi codegen FH1 (`config/rexglue/*.toml`), daftar dump & title update yang
  didukung (dengan hash), perbaikan runtime khusus FH1 (renderer native, XMA, UI);
- **port Android**: `pinyon.py android build` mengompilasi kode hasil codegen + runtime
  dengan NDK menjadi `libmain.so`, `librexruntime.so`, `librexgpu-fh1.so`, facade
  Speech/XMedia, hook libadrenotools; `PinyonShiftActivity` (SDL3) memuatnya;
- memori tamu Android lewat `memfd`/reservasi, driver Vulkan kustom (Turnip) lewat
  libadrenotools, kontrol sentuh, pengaturan Android (MSAA, bayangan, refleksi, single-pass).

Status menurut dokumentasinya: alpha untuk developer; diuji di Snapdragon 8 Elite dan
AYN Odin 2 Portal; perangkat 8 GB belum dipastikan.

## Keputusan

FHorizon Mobile **tidak** menulis recompiler atau runtime Xbox 360 baru. Backend-nya adalah
pipeline Pinyon Shift + ShiftGlue yang sudah terbukti, dan launcher ini membangun di
atasnya:

1. **Pipeline build modul** (`tools/build-module.*`, `tools/fhorizon_module.py`):
   menjalankan alat Pinyon Shift pada commit yang dipin dan membungkus pustaka Android
   hasilnya sebagai modul.
2. **Pemuat modul** (`FHorizonGameActivity`): memuat pustaka dari penyimpanan internal
   aplikasi dalam urutan dependensi yang dihitung inspektor ELF, lalu SDL memanggil
   `SDL_main` game.
3. **Integrasi Android**: pemindaian dan validasi file, probe Vulkan dengan syarat yang sama
   dengan `VulkanDevice::CreateIfSupported`, pengaturan grafis sebagai cvar nyata, kontrol
   sentuh sebagai gamepad SDL, gamepad, save, driver.

Alasan kode game tidak bisa dibangun di GitHub Actions: codegen membutuhkan `default.xex`
yang sudah didekripsi dari disc pemain, dan hasilnya (C++ dan .so) adalah turunan karya
berhak cipta. Pinyon Shift menerapkan aturan yang sama (repository mereka menolak paket
Android dan modul).

## Hal yang masih membutuhkan reverse engineering / pekerjaan lanjutan

Dari backlog Pinyon Shift dan pengamatan sendiri, relevan untuk Adreno 650:
- pengukuran memori nyata di perangkat 8 GB (AP-2.5, AP-7.6 di backlog mereka);
- jalur resolve EDRAM yang lebih murah untuk GPU tile-based (A60-11: GMEM-resident
  resolves) — penting untuk Adreno 6xx yang bandwidth-nya lebih kecil;
- transcode tekstur BC ke ASTC/ETC2 untuk GPU tanpa BC (Adreno punya BC, jadi bukan
  pemblokir untuk Pad 6);
- audio gameplay (EPIC XMA di `config/rexglue/`);
- kualitas bayangan bertingkat (saat ini hanya aktif/mati di backend).

## Sumber

- https://github.com/hedge-dev/XenonRecomp
- https://github.com/rexglue/rexglue-sdk
- https://github.com/arcanite24/pinyon-shift (`docs/ANDROID.md`, `docs/ANDROID_PORT_BACKLOG.md`, `tools/pinyon_android.py`)
- https://github.com/arcanite24/shiftglue-sdk (`src/ui/vulkan/vulkan_device.cpp`, `src/ui/vulkan/android_gpu_driver.cpp`)
- https://github.com/Luiz3du/ForzaHorizonRecomp

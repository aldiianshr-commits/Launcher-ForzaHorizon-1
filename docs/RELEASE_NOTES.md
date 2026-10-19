FHorizon Mobile 0.1.0 (alpha) — launcher Android ARM64 untuk Forza Horizon (Xbox 360) lewat static recompilation (ReXGlue/ShiftGlue + Pinyon Shift).

**APK ini tidak berisi game.** Kode game hasil rekompilasi dibangun di PC Anda dari disc Anda sendiri dengan `tools/build-module.sh` / `tools/build-module.ps1`, lalu diimpor di menu Backend. Lihat README.

Status: launcher, pemuat modul, pemindaian/verifikasi file, pengaturan grafis (cvar backend), kontrol sentuh, gamepad, save dan log sudah diuji otomatis di CI. Forza Horizon **belum pernah dijalankan** oleh proyek ini; Snapdragon 870 / Adreno 650 belum diuji siapa pun. Lihat docs/COMPATIBILITY.md.

APK ditandatangani dengan kunci debug CI (belum ada kunci rilis tetap): untuk memperbarui ke build berikutnya mungkin perlu uninstall dulu; data di /storage/emulated/0/FHorizon tetap aman.

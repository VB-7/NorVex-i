# NorVex-I

Aplikasi Android untuk generator Perchance **norvex-i** (Image Studio).
Web dibungkus jadi aplikasi mandiri: bersih dari menu/iklan Perchance,
unduh gambar ke folder sendiri, backup galeri otomatis, fallback ke
penyimpanan app bila folder utama gagal ditulis.

## Unduh & pasang

Ambil APK terbaru di halaman **[Releases](../../releases)**.
Pasang di HP, buka, langsung pakai. Update berikutnya ditawarkan sendiri
oleh aplikasi (tiap buka dicek; tinggal ketuk "Pasang" sekali).

## Update otomatis (seperti NorVex-R)

- Tiap dibuka, aplikasi cek `https://api.github.com/repos/<UPDATE_REPO>/releases/latest`
  (maks 1x per 10 menit). Kalau tag terbaru (mis. `b7`) lebih besar dari
  versionCode terpasang, APK langsung **diunduh di dalam aplikasi**
  (notifikasi DownloadManager) — tanpa buka GitHub, tanpa ditanya.
- Setelah unduh sukses (status dicek beneran + file ada + berisi),
  muncul dialog **"Update b7 siap dipasang — Pasang sekarang?"**.
  Ketuk **Pasang** → installer sistem dibuka.
- Android 8+: pertama kali akan diminta **"Izinkan aplikasi ini memasang
  update"** (unknown sources) → izinkan → buka lagi aplikasinya.
- File update: `getExternalFilesDir/update/norvex-i.apk` (ASD, aman).
  Tag yang sudah terunduh dicatat di `upd_tag` agar tak unduh ulang.
- Syarat: rilis GitHub harus ada (workflow di bawah membuatnya otomatis
  sebagai `NorVex-I b<N>` tiap push). Tanpa rilis → tak ada update.
- **PENTING:** edit `UPDATE_REPO` di
  `app/src/main/java/com/norvex/image/MainActivity.java`
  sesuai repo kamu, mis. `"USERNAME_KAMU/NorVex-I"`.
  Bawaan: `"VB-7/NorVex-i"`.

## Folder di HP

Utama: `/storage/emulated/0/perchance_app/Generete_Image/`
(posisi ini yang kamu minta; ejaan `Generete_Image` ikut spec kamu)

- `Generation/` — hasil generate, nama `Generete_<timestamp>_<n>.<ext>`
  contoh: `Generete_1700000000000_1.jpg`
- `Backup_Gallery/backup_gallery.json` — arsip galeri (JSON array
  `{id, image, metadata}`) + salinan `backup_gallery.bak`
- Bila folder utama gagal ditulis (izin/Scoped Storage), otomatis
  fallback ke ASD (app-specific, `getExternalFilesDir`), pola sama
  seperti NorVex-r. Toast memberi tahu posisi aktif.

## Cara kerja backup (baru, tanpa MIT)

- Web mengekspos `window.__imageStudio.getGallery()` /
  `restoreItems([...])` (sudah ada di generator).
- Native menarik galeri via `evaluateJavascript` saat halaman selesai
  dimuat, berkala, dan saat app dijeda → tulis `backup_gallery.json`
  di thread background.
- Saat start: baca backup → panggil `restoreItems(backup)` → galeri kembali.

## Bangun sendiri (cloud, tanpa Android Studio)

1. Repo GitHub baru → upload file-file ini.
2. Tab Actions → **Build APK** → Run workflow.
3. Ambil `NorVex-I-debug.apk` dari Artifacts (rilis: buat key sekali,
   lihat workflow `build-apk.yml`).

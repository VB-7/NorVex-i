# NorVex-I

Aplikasi Android untuk generator Perchance **norvex-i** (Image Studio).
Web dibungkus jadi aplikasi mandiri: bersih dari menu/iklan Perchance,
dua tombol dua nasib (**save** → galeri, **unduh** → file),
backup galeri otomatis (chunk, hash-gated), semua diam di background.

## Unduh & pasang

Ambil APK terbaru di halaman **[Releases](../../releases)**.
Pasang di HP, buka, langsung pakai. Update berikutnya dipasang sendiri
oleh aplikasi (tiap buka dicek; tanpa dialog, tanpa ditanya).

## Update otomatis (diam, tanpa dialog)

- Tiap dibuka, aplikasi cek `https://api.github.com/repos/<UPDATE_REPO>/releases/latest`
  (maks 1x per 10 menit). Kalau tag terbaru (mis. `b7`) lebih besar dari
  versionCode terpasang, APK langsung **diunduh di dalam aplikasi** — diam.
- Setelah unduh sukses (status dicek beneran + file ada + berisi),
  installer sistem langsung dibuka. Itu UI OS (wajib), bukan dialog app.
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

- `Generation/` — hasil tombol **unduh**, nama `Generete_<timestamp>_<n>.<ext>`
  contoh: `Generete_1700000000000_1.jpg` (+ `.nomedia` agar tak menyampah ke galeri HP)
- `Backup_Gallery/` — arsip galeri tombol **save**: `part-000.json`,
  `part-001.json`, … @100 item + `manifest.json` (+ `manifest.bak`
  fallback yang benar dipakai) + `.nomedia`
- Semua operasi diam (tanpa toast/dialog): hanya `trace.txt`/`error/`
  yang mencatat. Hasil simpan file dilaporkan ke pill status di web.
- Bila folder utama gagal ditulis (izin/Scoped Storage), otomatis
  fallback ke ASD (app-specific, `getExternalFilesDir`), pola sama
  seperti NorVex-r.
- Izin diminta SEKALI saja: tulis (Android 9/10), notifikasi (Android 13+).

## Cara kerja backup (lazy, tanpa batas jumlah)

- Web (`index.html`) render lazy: DOM hanya jendela 40 teratas (+40 per
  scroll via IntersectionObserver); arsip penuh bisa ribuan item.
- Native menarik **hash dulu** (`getStateMeta`): sig sama → lewati total.
  Beda → tarik 5-per-panggil (`getItemsJSON`, fallback 1-per-1),
  tulis per 100 ke part file. Galeri dikosongkan pun ditulis
  (manifest count 0) → tidak ada item hidup-lagi.
- Scroll melewati cache web → web menarik 5-per-panggil dari native
  (`NorVexI.getBackupRangeJSON`) sampai batas `nativeTotal`.
- API hook: `getLength/getSig/getStateMeta/getItemsJSON`,
  `restoreItems(arr, replace)` (`true` = ganti total termasuk kosong),
  `setNativeTotal(n)`, `onNativeSaved(name, ok)`.
- Gagal muat (offline): dialog **Coba lagi / Tutup**. Renderer mati:
  WebView dibangun ulang. Crash tercatat di `error/crash-<tanggal>.txt`.

## Bangun sendiri (cloud, tanpa Android Studio)

1. Push ke repo → workflow **Build APK** jalan otomatis.
2. Run pertama membuat kunci → simpan isi artifact `kunci-tanda-tangan`
   ke secret `KEYSTORE_B64` → jalankan ulang workflow.
3. Mulai run kedua, APK terbit otomatis di **Releases** (`NorVex-I b<N>`).

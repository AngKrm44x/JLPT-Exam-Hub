# JLPT Exam Hub – Android (WebView)

App = cangkang native ke web live. Soal, lock ujian, maintenance semua dari web/Supabase → otomatis sama di Android.

## Setup (sekali)
1. Edit `gradle.properties`: `SITE_URL` dan `GITHUB_REPO` (username/repo).
2. Upload folder ini ke repo GitHub (public, supaya cek update jalan tanpa token).
3. Tab **Actions** → jalan otomatis saat ada tag.

## Rilis baru (tiap update)
```
git tag v1.0.1
git push --tags
```
GitHub Actions build APK → buat Release → app user otomatis munculin popup (isi = catatan rilis).
- Edit catatan rilis di halaman Release GitHub = isi popup.
- Tulis `[wajib]` di catatan rilis → popup tidak bisa ditutup.

Versi pertama: tag `v1.0.0`, install APK-nya di HP. Min Android 7.0.

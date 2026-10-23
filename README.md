# JLPT Exam Hub – Android native (Kotlin + Jetpack Compose)

UI native. Data dari Supabase yang sama dengan web: soal, lock, maintenance, simpan progres.

## Rilis
```
git tag v1.0.0
git push --tags
```
GitHub Actions build APK, buat Release, app user otomatis munculin popup update
(isi popup = catatan rilis; tulis `[wajib]` supaya tidak bisa ditutup).

Build manual tanpa rilis: tab Actions > Build APK & Release > Run workflow > ambil APK di Artifacts.

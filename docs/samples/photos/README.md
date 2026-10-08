# dHash calibration samples

Drop sample photos here (`.jpg` / `.jpeg` / `.png`) to calibrate the per-item `dhash_threshold`.

Include realistic pairs:
- the **same scene** re-shot a moment later,
- a **re-compressed** copy (e.g. re-saved as JPEG),
- a **slightly cropped / re-angled** copy,
- clearly **different scenes**.

Then run (from `backend/`):

```
./gradlew dhashCalibration                 # reads ../docs/samples/photos
./gradlew dhashCalibration -Pdir=/abs/path # or an explicit folder
```

It prints a pairwise Hamming-distance matrix. Pick a threshold that sits comfortably **above** the
distances of true duplicates and **below** the distances of genuinely different scenes. Start at 6.

This is a manual tool — it is **not** a test and is **not** part of CI. Real photos are not committed
(see `.gitignore` in this folder); only this README is tracked.

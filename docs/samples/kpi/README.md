# KPI score-sheet samples

Drop the real KPI files here — **one `.xlsx` per role**, e.g.:

```
docs/samples/kpi/chef.xlsx          # Повар мангалщик / шеф
docs/samples/kpi/line-cook.xlsx     # линейные повара
docs/samples/kpi/hall.xlsx          # зал
docs/samples/kpi/management.xlsx    # управление
```

These feed P2-6b (`score_sheet`): each sheet maps to one score sheet (role name ru/uz), its rows to
score-sheet items (a `checklist_item` + its `points`, summing to 100), and its bands to the
bonus/base/penalty scale. See `docs/DECISIONS.md` → “P2-6b” for the mapping.

Real files are **gitignored** (they may hold internal data); only this README is tracked. The xlsx are
reference material for building the model by hand — there is **no automated xlsx import**.

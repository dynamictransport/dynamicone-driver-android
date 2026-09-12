# Git Workflow

## Repository

Önerilen isim:
`dynamicone-driver-android`

Remote provider:
- GitHub private repo
veya şirket standardı varsa Azure DevOps.

## Branches

- `main` — production-ready
- `develop` — opsiyonel; küçük ekipte şart değil
- `feature/<name>`
- `fix/<name>`

Küçük ekip için trunk-based yaklaşım da uygundur:
kısa ömürlü feature branch → PR → main.

## Commit örnekleri

- `feat(auth): add login flow`
- `feat(trips): add trip acceptance`
- `feat(sync): queue offline driver actions`
- `fix(documents): retry failed image upload`
- `test(trips): cover duplicate action prevention`
- `docs(api): document driver trip actions`

## PR

Her PR:
- amaç
- ekran görüntüsü (UI ise)
- test sonucu
- API değişikliği
- migration varsa not

## Secrets

Commit edilmez:
- appsettings
- real base URL secret
- API key
- Firebase service account
- signing key
- keystore password

## CI

`.github/workflows/android-ci.yml` başlangıç şablonudur.

CI:
- test
- lint
- debug build

Production signing ayrı secret store üzerinden eklenir.

## Release

İlk saha dağıtımı:
- Internal testing / Managed Google Play / doğrudan kontrollü enterprise dağıtım seçenekleri değerlendirilebilir.
- Release version code otomatik artırılmalı.

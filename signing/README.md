# Release signing

`buxes-release.p12` signs every Buxes release, so a new version installs over the old one and
keeps your library. The password is in `keystore.properties.ci` next to it.

This is a personal, sideloaded app, so the key ships with the repository for the GitHub Actions
build. To keep it private instead: move the file and password to the repository secrets
`BUXES_KEYSTORE_B64`, `BUXES_KEYSTORE_PASSWORD`, `BUXES_KEY_ALIAS`, `BUXES_KEY_PASSWORD` (the
workflow prefers them) and delete this folder. Never change the key: a release signed with another
key can't install over an existing Buxes.

# Release Information

## Performing a Release

To release a new version of `cdk-constructs`, please create a PR for the `release` branch.

Once it's merged, we'll automatically deploy a new version to Maven Central.

## GPG Key Information

- The current GPG key was created on Philip's MacBook Pro M1 laptop
- Key ID: `081897A195B1450E`
- Both the exported GPG key and the secret passphrase are stored as GitHub Secrets for this repository
- Extract the GPG private key with `gpg --armor --export-secret-keys YOUR_ID`

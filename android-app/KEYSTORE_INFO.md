# Android Keystore Information

⚠️ **SECURITY NOTE:** Keep this file safe! Never commit it to git.

## Keystore Details

**Location:** `android-app/rdm-release.keystore`

**Alias:** `rdm`
**Store Password:** `rdmStorePass2024!`
**Key Password:** `rdmStorePass2024!` (same as store password)

## Certificate Info

- **Valid for:** 10,000 days (until July 10, 2053)
- **Algorithm:** RSA 2048-bit
- **SHA-256 Fingerprint:** `B1:F7:99:91:FF:F4:62:CB:67:68:81:AB:5A:2A:D9:3F:6C:9F:A1:61:34:C7:ED:16:F2:EF:B9:EE:17:89:84:03`

## CodeMagic Setup

1. Go to CodeMagic → User settings → Codemagic signing keys
2. Upload `android-app/rdm-release.keystore`
3. Give it a name (e.g., "rdm-keystore")
4. Update `codemagic.yaml`:

```yaml
environment:
  android_signing:
    - keystore_reference: rdm-keystore
  vars:
    KEYSTORE_ALIAS: rdm
    KEYSTORE_PASSWORD: rdmStorePass2024!
    KEY_PASSWORD: rdmStorePass2024!
```

5. Add these as environment variables in CodeMagic app settings:
   - `KEYSTORE_ALIAS` = `rdm`
   - `KEYSTORE_PASSWORD` = `rdmStorePass2024!`
   - `KEY_PASSWORD` = `rdmStorePass2024!`

## Important Notes

- ⚠️ **Backup this keystore!** If you lose it, you won't be able to update your app
- ⚠️ **Never commit this file or the keystore to git** (already added to .gitignore)
- The keystore is in PKCS12 format
- Store and key passwords are the same (PKCS12 requirement)

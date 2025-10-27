# Feature: CBOR Serialization for NetworkMessage

## Goals

- Replace JSON serialization with CBOR for `NetworkMessage` objects.
- Reduce message size and improve serialization/deserialization performance.
- Maintain JSON serialization for `JavaScriptInjectedAndroid`.

## Design

- Add the `kotlinx-serialization-cbor` dependency to the project.
- Update the `NetworkMessage.kt` file to use `kotlinx.serialization.cbor.Cbor` for `toByteArray` and `fromByteArray` methods.
- No changes to `JavaScriptInjectedAndroid.kt`.

## Checklist

- [X] Create `CBOR.md`
- [X] Add `kotlinx-serialization-cbor` dependency to `app/build.gradle.kts`.
- [X] Modify `NetworkMessage.kt` to use `Cbor` instead of `Json`.
- [X] Verify the change by building the project.
- [X] Complete pre-commit steps.
- [X] Submit the change.

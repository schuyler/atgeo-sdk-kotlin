# atgeo-client for Kotlin

A Kotlin client for the atgeo gazetteer served by [garganorn](https://github.com/schuyler/garganorn), which searches places without telling the server what you searched for. It is a port of [@atgeo/client](https://github.com/schuyler/atgeo-sdk-js), runs on the JVM (11 or later) and on Android (API 24 or later), and gives the same answers for the same server responses.

Status: under construction. Nothing here is usable yet.

The design lives in the JavaScript repository: [docs/kotlin-sdk-spec.md](https://github.com/schuyler/atgeo-sdk-js/blob/main/docs/kotlin-sdk-spec.md) for the Kotlin API and [docs/port-contract.md](https://github.com/schuyler/atgeo-sdk-js/blob/main/docs/port-contract.md) for the behaviour every port shares.

## Building

```sh
./gradlew build                          # tests, plus the Android API 24 check
./gradlew -p android-sample assembleDebug  # needs an Android SDK
```

## License

BSD 3-Clause; see [LICENSE](LICENSE).

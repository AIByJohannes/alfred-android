# A.L.F.R.E.D. App for Android
![Kotlin](https://img.shields.io/badge/-Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)
![Gradle](https://img.shields.io/badge/-Gradle-02303A?style=flat&logo=gradle&logoColor=white)

## Development Setup

To easily interact with the OpenRouter API during development without manually typing the API key in the app:
1. Create or open the `local.properties` file in the root directory.
2. Add your OpenRouter API key:
   ```properties
   OPENROUTER_API_KEY=sk-or-v1-your-key-here
   ```
This key will be bundled directly into your debug builds.

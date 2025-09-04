Project setup & secure Firebase configuration

This project is set up to avoid committing Firebase credentials into source control.
Follow these steps to build and run locally, and in CI.

1) Add secrets to local.properties (DO NOT commit this file)

In the repository root create or edit local.properties (it is in .gitignore). Add the following keys:

firebaseApiKey=AIza...
firebaseWebClientId=1234567890-xxxxxxxxxxxxxxxxxxxxxxxx.apps.googleusercontent.com
firebaseProjectNumber=367090623924
firebaseProjectId=stravaconle
firebaseStorageBucket=stravaconle.firebasestorage.app

2) Remove any committed google-services.json (one-time)

If app/google-services.json is currently tracked by Git, remove it from the index but keep it locally (or delete it):

# remove from git but keep the file locally
git rm --cached app/google-services.json
git commit -m "Remove generated google-services.json from VCS"

This ensures secrets are not stored in the repo. The build generates minimal resource values from local.properties during Gradle configuration.

3) Build locally

From the project root run:

# Windows PowerShell
./gradlew.bat clean assembleDebug

or use Android Studio: Build -> Clean Project -> Rebuild Project

4) CI (GitHub Actions)

A workflow file has been added at .github/workflows/ci.yml. In your repository settings add the following secrets:
- FIREBASE_API_KEY
- FIREBASE_WEB_CLIENT_ID
- FIREBASE_PROJECT_NUMBER
- FIREBASE_PROJECT_ID
- FIREBASE_STORAGE_BUCKET

The workflow writes local.properties from secrets and runs assembleDebug.

5) Notes about the implementation

- The project no longer relies on the google-services Gradle plugin or an app/google-services.json committed into the repo. Instead:
  - BuildConfig fields (FIREBASE_API_KEY, FIREBASE_WEB_CLIENT_ID, etc.) are populated from local.properties at build time.
  - A small values.xml with default_web_client_id and google_api_key is generated under build/generated/res/google-services/<variant>/values so R.string.default_web_client_id is available to the app.
  - MainActivity initializes Firebase at runtime using FirebaseOptions built from BuildConfig values when no FirebaseApp exists.

- This approach keeps secrets out of the repository but still allows Google Sign-In and Firebase Auth to work.

6) If you want stricter security

- Use CI secrets (as the workflow does) and avoid putting secrets on developer machines.
- Consider using a secure parameter store (AWS Secrets Manager, Google Secret Manager) and populate local.properties in CI/CD just before the build.

If you'd like, I can:
- Remove any tracked google-services.json automatically (make a code change that runs git via a gradle task) — note: running git from CI may need credentials.
- Add a small unit / integration test for the login/profile UI.
- Tighten dependency management (migrate fully to the version catalog in build files).

Which of the above would you like me to do next?

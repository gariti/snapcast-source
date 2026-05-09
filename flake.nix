{
  description = "Snapcast Source — Android dev shell (JDK + Gradle + Android SDK)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";

      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" "34" ];
        buildToolsVersions = [ "35.0.0" "34.0.0" ];
        platformToolsVersion = "35.0.2";
        cmdLineToolsVersion = "13.0";
        includeNDK = false;
        includeEmulator = false;
        includeSystemImages = false;
        includeSources = false;
      };

      androidSdk = androidComposition.androidsdk;
      sdkPath = "${androidSdk}/libexec/android-sdk";
      buildToolsVersion = "35.0.0";
    in {
      devShells.${system}.default = pkgs.mkShell {
        buildInputs = [
          pkgs.jdk17
          pkgs.gradle
          androidSdk
        ];

        ANDROID_HOME = sdkPath;
        ANDROID_SDK_ROOT = sdkPath;
        JAVA_HOME = "${pkgs.jdk17}/lib/openjdk";

        # AGP downloads its own aapt2 from Maven by default; that binary is
        # unpatched ELF and won't run on NixOS. Point it at the SDK's wrapped one.
        GRADLE_OPTS =
          "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkPath}/build-tools/${buildToolsVersion}/aapt2";

        shellHook = ''
          # Pin Gradle to the Nix-store SDK while in this shell. Android Studio
          # will overwrite this on next IDE launch — that's fine, this re-claims
          # it whenever you re-enter `nix develop`.
          expected="sdk.dir=${sdkPath}"
          if [ ! -f local.properties ] || [ "$(head -1 local.properties 2>/dev/null)" != "$expected" ]; then
            echo "$expected" > local.properties
          fi

          cat <<EOF
        snapcast-source dev shell
          JDK:          $JAVA_HOME
          ANDROID_HOME: $ANDROID_HOME
          adb:          $(command -v adb)
          gradle:       $(command -v gradle)

        First-time:   gradle wrapper --gradle-version 8.10.2
        Build debug:  ./gradlew assembleDebug
        Install:      adb install -r app/build/outputs/apk/debug/app-debug.apk
        Logs:         adb logcat | grep -E 'snapcastsource|TcpStreamer|AudioCapture'
        EOF
        '';
      };
    };
}

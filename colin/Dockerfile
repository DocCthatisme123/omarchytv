# Build environment for Colin's OmarchyTV fork: JDK 17 + Android SDK 34. Nothing installed on the host.
FROM eclipse-temurin:17-jdk-jammy
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/34.0.0:$ANDROID_HOME/platform-tools
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl git python3 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p $ANDROID_HOME/cmdline-tools \
 && curl -fsSL -o /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q /tmp/clt.zip -d $ANDROID_HOME/cmdline-tools && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest \
 && rm /tmp/clt.zip \
 && yes | sdkmanager --licenses >/dev/null \
 && sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null
WORKDIR /src

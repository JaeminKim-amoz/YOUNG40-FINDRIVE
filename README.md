# FINDRIVE

FINDRIVE는 보호자의 실종 신고와 차량의 이동 기록을 연결해, 다음에 살펴볼 도로를 Pleos 내비게이션으로 안내하는 YOUNG40의 차량 앱입니다.

## 심사용 APK 설치와 실행

[차량용 release APK 다운로드](./apk/YOUNG40_FINDRIVE_release.apk?raw=true) · [시연 영상](https://youtu.be/82D3r7VGGyM)

공식 Pleos Connect 에뮬레이터를 부팅하고 Android SDK Platform Tools가 있는 터미널에서 실행합니다.

```sh
adb devices
adb install -r apk/YOUNG40_FINDRIVE_release.apk
adb shell am start -W -n app.findrive.car.challenge/app.findrive.car.MainActivity
```

앱 버전은 0.5.0(5)입니다. Google Play나 휴대폰 로그인 없이 설치할 수 있습니다. 제출용 패키지는 개발판과 함께 설치됩니다. APK의 SHA-256은 `da07daf8927d2c1567140f011a80b3a303f1f1c7887c1298dace008968a00d01`입니다.

## 3분 시연

1. Virtual Console에서 기어를 P로 두고 FINDRIVE를 엽니다. 차량 신호가 들어오면 **공유에 동의하고 시작하기**를 누릅니다. 차량은 자체 기기 세션으로 연결됩니다. 휴대폰과 같은 자료를 쓰려면 휴대폰에서 만든 연결 코드를 차량에 입력합니다.
2. 위치 사용을 허용하고 **주변 수색**에서 신고를 엽니다. 주변에 공유된 신고가 없으면 빈 목록을 표시합니다. 주변 신고가 없는 경우 위 시연 영상에서 추천 코스와 주행 기록을 확인할 수 있습니다.
3. **반경 내 도로 수색 → 수색 코스 추천**으로 이동합니다. 반경은 100m부터 10km까지입니다. **추천 코스로 안내**를 누르고 목적지를 확인하면 차량 기본 내비게이션으로 이어집니다. 동의 후 시작한 주행 기록은 FINDRIVE 지도에 표시되며 시간이 지나면 흐려집니다.
4. 에뮬레이터에서 D로 바꾸면 상세 조작이 잠깁니다. P로 돌아온 뒤 차량 신호가 확인되면 다시 사용할 수 있습니다.

## 시험 환경과 빌드

| 항목 | 제출판 기준 |
| --- | --- |
| 차량 플랫폼 | Pleos Connect 2.1.0 공식 ARM64 이미지, Android 14(API 34), RAM 4 GB |
| 화면 | 에뮬레이터 기본 디스플레이 2560 × 1440 px, 160 dpi, 가로. 시스템 분할 창은 앱에 더 작은 영역을 배정할 수 있습니다. |
| 호스트 | macOS Apple Silicon, Android Emulator 36.5.11 |
| 빌드 | JDK 17, Gradle Wrapper 8.10.2, Android SDK 35, 최소 API 28 |
| SDK | Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3 |
| 연결 | HTTPS, 위치 사용 동의, P단 및 차량 주의 분산 제한 신호. 외부 지도·도로 서비스는 인터넷이 필요합니다. |

```sh
cd car
./gradlew :app:testPleosReleaseUnitTest :app:assemblePleosRelease \
  -PfindriveApplicationId=app.findrive.car.challenge \
  -PfindriveServiceUrl=https://your-findrive-server.example
```

직접 빌드할 때는 마지막 값을 자신의 FINDRIVE HTTPS 서버 주소로 바꿉니다. Gradle이 만드는 `app-pleos-release-unsigned.apk`는 Android Build Tools의 `apksigner`와 자신의 키로 서명할 수 있습니다. 위에 링크한 제출판은 팀 서명이 적용되어 있습니다.

## 위치와 주행 기록

일반 참여자는 약 1km 단위의 수색 구역을 확인합니다. 신고 보호자와 그 휴대폰에 연결한 차량은 정확한 신고 위치를 볼 수 있습니다. 목격자는 자신이 남긴 제보의 정확한 위치를, 주행 참여자는 본인 계정의 이동 기록을 확인할 수 있습니다. 운영자는 인증 후 검토할 수 있습니다.

차량은 HTTPS로 심사 서버에 연결합니다. 에뮬레이터에서 주차(P) 상태로 공유에 동의하면 시작할 수 있습니다. 근처에 신고가 있어야 수색 코스를 선택할 수 있습니다. 기본 시연 서버는 임시 HTTPS 주소를 사용해 가용 시간이 달라질 수 있습니다.

공식 에뮬레이터에서 제출 APK 설치와 실행을 확인했습니다. 같은 차량 소스의 개발 패키지에서 P/D 잠금 전환과 내비게이션 경로 시작 응답을 확인했습니다. [공모전 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission) · [Pleos 공식 SDK 예제](https://github.com/Pleos-Playground)

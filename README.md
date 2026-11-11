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

앱 버전은 0.5.2(7)입니다. Google Play나 휴대폰 로그인 없이 설치할 수 있습니다. 제출용 패키지는 개발판과 함께 설치됩니다. APK의 SHA-256은 `8101265c89dcfd72f805af7a988be8d53a68bd590d96e706e8ea84826eff1707`입니다.

## 3분 시연

1. 공식 에뮬레이터의 Virtual Console에서 기어를 P로 두고 앱을 엽니다. **예시 코스로 체험하기**를 누릅니다. 휴대폰, 계정, 신고 등록, 주소 입력 없이 시작할 수 있습니다.
2. 서울숲 반경 500m에서 미리 계산한 약 1.6km의 추천 도로와 지도를 확인합니다. **예시 코스로 안내**를 누르면 Pleos 기본 내비가 현재 내비 위치에서 첫 경유지까지 안내합니다. 내비 첫 화면의 예상 거리에는 코스 진입 전까지의 거리도 포함됩니다. 예시 체험은 차량 이동 기록을 수집하지 않습니다.
3. 실제 신고로 수색하려면 **공유에 동의하고 시작하기**를 누르고 위치 사용을 허용합니다. 반경 5km 안의 신고를 열어 **반경 내 도로 수색 → 수색 코스 추천 → 추천 코스로 안내**를 선택합니다. 주변 신고가 0건인 화면에도 **예시 코스로 체험하기**가 있습니다.
4. Virtual Console에서 D로 바꾸면 상세 조작이 잠깁니다. P로 돌아오면 차량 신호를 확인한 뒤 조작할 수 있습니다.

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

일반 참여자는 약 1km 단위의 수색 구역을 확인합니다. 신고 보호자와 그 휴대폰에 연결한 차량은 정확한 신고 위치를 볼 수 있습니다. 목격자는 자신이 남긴 제보의 정확한 위치를, 주행 참여자는 본인 계정의 이동 기록을 확인할 수 있습니다. 운영자는 인증 후 검토할 수 있습니다. 차량은 앱을 다시 열 때 서버에 권한을 확인하고 신고와 경로를 새로 받습니다.

심사용 고정 주소는 [findrive-review.jkim4430.workers.dev](https://findrive-review.jkim4430.workers.dev/health)입니다. 예시 코스는 클라우드에서 제공하며 접속하지 못하면 APK에 포함된 같은 예시를 엽니다. Pleos 길 안내에는 차량의 내비게이션 서비스가 필요합니다. 실제 신고와 주행 기록 동기화에는 팀의 비공개 서버 연결이 필요합니다.

공식 Pleos 에뮬레이터에서 신고가 없는 상태로 예시 코스를 열고 기본 내비게이션이 첫 경유지 안내를 시작하는 것을 확인했습니다. [공모전 제출 안내](https://appdevchallenge.auto-hmg.io/guide/submission) · [Pleos 공식 SDK 예제](https://github.com/Pleos-Playground)

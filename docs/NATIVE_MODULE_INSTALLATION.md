# 네이티브 모듈 설치와 의존성 관리

현재 기본 서버는 Node.js + React다. 아래 Java/Gradle 절차는 이전 구현 기록이다. Node의 FFmpeg/FFprobe 실행에는 추가로 [Windows Job 실행기](../native/process-job/README.md)가 필요하며, `scripts/build-process-job.ps1`로 로컬 빌드한다. 일반 실행은 설치 YAML에 실행기 경로·SHA-256을 지정하고, 개발 실행만 생성 manifest를 읽는다. 실행기 없이 직접 실행하는 자동 우회는 없다.

후속으로 [Java 격리 어댑터](LIBVIPS_JAVA_SANDBOX_ADAPTER.md)를 구현했습니다. 아래 web SDK 설치와 별개의 최소 contained 런타임을 사용하며 서버 factory 활성화나 SDK 자동 우회는 하지 않습니다.

2026-11-12. Windows x64 개발 설치를 준비했다. 기존 D:/DG_module, 시스템 PATH, 실행 중인 서버, 실제 설치 YAML을 변경하지 않는다. EXE/DLL/다운로드 ZIP은 Git과 실행 JAR에 넣지 않는다. 설치 ZIP 생성 단계가 아니다.

## Java import와 배포 방식

FFmpeg의 JavaCPP preset 같은 Java 바인딩을 Gradle 의존성으로 가져올 수는 있지만, 네이티브 코드·OS별 바이너리·각 구성요소의 라이선스는 여전히 필요하다. JNI/FFM으로 서버 JVM에 로드하는 방식은 네이티브 오류와 메모리 사용이 서버 프로세스에 직접 영향을 줄 수 있다. JDK21 고정 조건과 현재의 별도 프로세스 제한·시작 해시·LPAC 설계를 유지한다.

이 프로젝트는 Java 런타임 의존성을 추가하는 대신 Gradle 설치/검증 작업으로 외부 모듈을 관리한다. 서버는 설정된 로컬 실행 파일만 ProcessBuilder 기반 기존 실행기로 호출한다. 일반 build/test 및 애플리케이션 시작에서는 다운로드하지 않는다. 최종 배포 시 OS별 외부 modules 디렉터리와 고지/소스를 관리할 수 있지만 현재 설치 결과를 그대로 재배포 승인으로 취급하지 않는다.

## 고정 패키지

| 모듈 | 고정 식별 | 설치 위치 |
| --- | --- | --- |
| FFmpeg/FFprobe | n8.1.3-27-g8dca1586f4-20261031 / Windows x64 LGPL shared | .tools/modules/ffmpeg-8.1.3-27-lgpl-shared/bin |
| libvips | 8.18.7 / Windows x64 web / shared | .tools/modules/libvips-8.18.7-web/bin |

공식 libvips 배포와 FFmpeg 공식 다운로드 페이지가 연결한 Windows 빌드 저장소의 날짜/버전 고정 URL을 사용한다. latest와 자동 대체 버전은 허용하지 않는다. 게시 SHA-256·크기와 출처·소스/빌드 레시피 링크는 config/oss/native-downloads.windows-x64.json에 기록했다. FFmpeg ZIP은 새로 받았고 libvips ZIP은 기존 공식 다운로드 캐시에서 동일한 게시 해시를 검증해 재사용했다. 해시 일치는 게시 파일 식별이며 서명·취약점·법적 검토 전체를 뜻하지 않는다.

## 설치와 검증

JDK21과 Windows x64 PowerShell 환경에서 프로젝트 루트를 기준으로 실행한다.

```powershell
# 최초 설치: 명시적으로만 네트워크 수신을 허용
./gradlew.bat installNativeModules -PdownloadNativeModules
# 캐시가 준비된 PC: 네트워크 없이 설치/재검증
./gradlew.bat installNativeModules
# 설치가 없으면 실패하며 다운로드/추출하지 않음
./gradlew.bat verifyNativeModules
```

- .tools/module-downloads의 원본 ZIP을 검증한 뒤 전용 임시 디렉터리에 추출하고 새 버전 폴더로 이름을 바꾼다. 다운로드는 게시 크기와180초 전체 기한으로 제한한다. 기존 버전/시스템 설치를 덮어쓰지 않는다. 설치 잠금으로 동시 설치를 거절하며 실패한 임시 폴더는 검사 가능하도록 보존한다. 자동 재귀 삭제하지 않는다.
- 추출 전에 전체 ZIP의 상위 경로·절대 경로·역슬래시·Windows 장치명·대소문자 충돌·심볼릭 링크·파일/전체 크기를 검사한다. Magick/Ghostscript 실행 파일·DLL을 거절한다. 인증된 공식 패키지에도 설정하지 않은 로더가 있을 수 있으므로 web 설치를 서버의 형식 승인으로 해석하지 않는다.
- 영수증만 신뢰하지 않고 설치 파일819개를 고정 ZIP의 각 엔트리 바이트와 다시 대조한다. 라이선스·문서·헤더도 원래 패키지 그대로 보존한다. 잘못된 영수증·추가/누락/변경 파일을 거절한다. 서비스 계정 전용 로컬 경로가 전제이며 악성 로컬 관리자와 경로 동시 교체 공격 전체의 방어를 주장하지 않는다.
- FFmpeg/FFprobe의 shared 옵션, GPL/nonfree 활성화 부재, LGPL 문구, 기존 재생에 필요한 libvpx/libopus/libmp3lame을 검사한다. libvips 버전과 실제 로더 목록에서 Magick/Ghostscript 부재를 확인한다.

결과: build/reports/native-installation/verification.json. `technicalMatch=true`는 위의 기술적 식별 성공이며 `distributionReady=false`를 유지한다. build의 check에는 합성 ZIP 방어 규칙11개가 포함되지만 실제 설치/네트워크 수신 작업은 포함되지 않는다.

최종 검증: 새 FFmpeg 경로를 지정한 FFmpeg/재생·설치 설정·실제 img2tile 집중90개·14 suites가 실패/오류/건너뜀0으로 통과했고, ZIP 방어11규칙·기존 라이선스16규칙과 함께 build가2분2초에 성공했다. 실제 MP4/MPEG/MP3/WAV의 합성 변환과 보호된 Spring 재생/Range API를 포함한다. libvips CLI는 프로젝트가 생성한 합성 JPEG를160×100 PNG로 변환했다. 크기 제한 다운로드 경로도 실제 공식 libvips ZIP에서 게시 해시 일치·잘못된 크기 거절을 확인했다. URL 작업 당시 전체946개 결과는 별도 이전 검증이며 이번 설치 단계의 전체 재실행으로 표기하지 않는다.

## IntelliJ에서 사용

설치 후 생성되는 build/native-modules/imagegate.native.yml에는 FFmpeg/FFprobe의 절대 경로·EXE SHA-256과 개발용 playback.enabled=true가 들어 있다. IntelliJ 실행 구성은 **SimpleImgViewer** 하나로 통합했으며 개발 로그인과 이 파일을 함께 적용한다. 기존 JVM은 중지 후 다시 실행해야 한다. clean으로 build를 지운 경우 installNativeModules를 다시 실행해 생성한다. dev 프로필만 켠 CLI 실행은 아래 추가 인자도 지정해야 한다.

일반 실행에서도 아래 인자를 추가할 수 있다.

```text
--spring.config.additional-location=file:./build/native-modules/imagegate.native.yml
```

개발 자동 로그인 조건/공유 관리자 범위는 기존 dev 정책을 따른다. 생성 파일을 운영 설치 설정으로 그대로 복사하지 않는다. libvips CLI는 설치·검사만 완료했으며 현재 Spring factory에서 libvips 선택을 허용하지 않는다. 다음은 승인된 최소 워커/LPAC 부모의 Java 어댑터와 권한·캐시·게시 연결이며, 이 SDK를 해당 워커로 자동 교체하거나 우회하지 않는다. HEIC/JPEG2000 전체 지원을 추가한 단계도 아니다.

## 라이선스와 최종 재배포 경계

FFmpeg는 LGPL shared 구성을 사용하고 libvips는 LGPL-2.1-or-later이지만 전이 라이브러리와 내장 코드에는 별도 조건이 적용된다. Java에서 import하거나 외부 프로세스로 실행하는 것만으로 고지/소스 제공 등의 의무가 사라지지 않는다. 로컬 개발 설치와 제3자 재배포를 구분한다.

재배포 전에는 정확히 대응하는 모든 전이 소스·패치·빌드 설정, 저작권/NOTICE/라이선스 전문, LGPL 구성요소 교체/소스 제공 방법, native 취약점과 코덱 특허 검토를 완료해야 한다. 바이너리 파일명이나 프로젝트 대표 라이선스만 보고 라이선스 문제가 없다고 승인하지 않는다. 현재 패키지·영수증·고정 해시를 최종 설치 ZIP에 자동 포함하는 작업은 제공하지 않는다. 최종 기능과 운영 인수를 마친 배포 단계에서 검토된 목록만 포함한다.

근거: [FFmpeg 공식 다운로드](https://ffmpeg.org/download.html), [FFmpeg 라이선스/소스 안내](https://ffmpeg.org/legal.html), [Windows 빌드 변형과 보존 정책](https://github.com/FFmpeg-Builds/FFmpeg-Builds), [libvips Windows 설치와 web/all 차이](https://www.libvips.org/install.html), [JavaCPP FFmpeg preset](https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg). Java 런타임 목록 검증은 [RUNTIME_LICENSES.md](RUNTIME_LICENSES.md)를 따른다.

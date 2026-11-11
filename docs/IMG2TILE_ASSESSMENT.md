# img2tile 사전 점검

최신 범위는 [형식별 지원표](FORMAT_SUPPORT.md)를 우선합니다. 후속 [JPEG2000 실제 모듈 검증](JPEG2000_NATIVE_COMPATIBILITY.md)에서 RGB8 JP2의 픽셀 일치와 원시 J2K의 native 거절을 확인했습니다. 아래 사전 점검 시점의 미검증 표와 구분합니다. 타일/UI·후속 성능 검증은 개발 계획의 최신 상태를 따릅니다.

## 확인한 범위

- 대상: D:/DG_module/img2tile/img2tile.exe, 바이너리는 프로젝트에 복사하지 않음.
- 폴더: img2tile.exe와 libtiff/libheif/libde265/openjp2/jpeg/png/lcms 등의 DLL,
  resource/cmyk.icc 및 resource/libc9i.xml.
- 현재 폴더에 소스코드·README·라이선스 문서는 없으나, 사용자가 다른 팀원이 개발한 내부 프로그램이며 사용에 문제가 없음을 확인함.
- 실행 파일의 사용법 문자열을 확인하고, 인자 없는 도움말 실행으로 아래 옵션·오류 코드 확인.
- 이 문서는 바이너리 문자열과 별도 도움말 점검을 구분하며, 실제 변환 성능/정확도를 보증하지 않음.

## 바이너리 내 사용법 문자열

~~~text
USAGE: [-page 0] [-coord 0 0] [-coord_level 0]
[-oname %%c_%%r.%%e] [-o1bit_levelimage] [-o1bit_tileimage]
[-o1bit_scaleimage] [-simulate] [-imaxdpi 0] [-oquality 85]
[-otilesize 256] [-odir directory] [-otype jpg] [-otype_tile jpg]
[-olevelimage -1] [-oscalesize 0] [-oscalename scale.jpg]
[-oscaleonly] [-mkdir.files] -f sample.jpeg
[-searchdir f:\contents -odrive c:]
~~~

- 합성 2페이지 TIFF에서 -page 0=첫 페이지, -page 1=둘째 페이지로 확인. 웹 API 페이지 p는 CLI p-1로 매핑.
- 타일 크기·레벨·축소 이미지 옵션과 DZI 형태 XML 문자열을 발견. 출력 호환성은 아직 미검증.
- -simulate 옵션 의미·종료 코드·오류 출력·리소스 경로 역시 실제 동작으로 확인 필요.
- ERROR_LICENSE는 프로그램의 오류 코드 중 하나입니다. 이 문자열 자체를 사용 제한의 근거로 삼지 않습니다.
- 사용자 확인에 따라 내부 프로그램 사용의 라이선스 대기를 해제하고 검증을 진행합니다.
- 내부 개발 프로그램이라는 확인은 해당 프로그램을 오픈소스로 분류하거나 포함 DLL의 라이선스를 개별 검증했다는 뜻은 아닙니다.

## 도움말 실행 결과

별도 작업 폴더에서 입력 파일 없이 실행, 제한 시간 8초 내 종료. 실제 종료 코드 -1.
표준 출력에 사용법 및 다음 결과 코드가 표시되었습니다. 이 결과는 라이선스 허가 또는 실제 변환 성공을 뜻하지 않습니다.

| 코드 | 의미 |
| --- | --- |
| 0 | ERROR_OK |
| -1 | ERROR_UNKNOWN |
| -2 | ERROR_DIR |
| -3 | ERROR_OPEN |
| -4 | ERROR_CREATE |
| -5 | ERROR_READ |
| -6 | ERROR_WRITE |
| -7 | ERROR_LICENSE |
| -8 | ERROR_PAGE |
| -9 | ERROR_COORD_RANGE |
| -10 | ERROR_COORD_LEVEL |
| 1 | ERROR_FORMAT |

## 후속 검증표

| 항목 | 검증 내용 | 상태 |
| --- | --- | --- |
| 라이선스 | 자체 프로그램 및 모든 DLL의 출처·버전·허용 조건 | 내부 프로그램 사용은 사용자 확인; 배포 목록은 최종 정리 |
| 실행 계약 | 입력/출력 인자, 종료 코드, 표준 출력, cwd·리소스 의존성 | 도움말 및 PNG/JPG 축소 변환 확인; 리소스 원본은 미변경 |
| 페이지 | 다중 페이지 TIFF, 0/1 기반 매핑, 페이지별 크기 | 합성 2페이지 TIFF에서 페이지 매핑·내용·범위 오류 확인 |
| 색상/방향 | ICC, CMYK, EXIF 방향, 투명도, 16bit/HDR 축소 | 샘플 검증 필요 |
| 타일 | 레벨·좌표·출력 명세와 OpenSeadragon 호환 | 샘플 검증 필요 |
| 성능 | 유형·픽셀·페이지별 최초 미리보기/P50/P95/최대 메모리 | 기준선 미측정 |
| 보안 | 경로 범위·출력 파일·자식 프로세스 종료·손상 파일 제한 | 격리 실행기 단계 |

## 성능 측정 기준

실제 업무 자료를 저장소에 복사하지 않고 허가된 샘플만 사용합니다.
형식/압축/픽셀/페이지/용량/출력/도구버전/동시성/콜드·웜 상태를 기록합니다.
합성 자료와 실제 업무 자료 결과를 구분합니다. 처리 가능한 자료만으로 성공률을 계산하지 않습니다.
최초 표시·페이지 전환·확대·저장 시간은 변환 시간과 별도 측정합니다.
사용자 규모와 네트워크 기준이 없으므로 현재 단계에서 임의의 성능 수치를 목표로 확정하지 않습니다.

## 합성 TIFF 변환 검증

실제 업무 자료 대신 JDK 21 ImageIO로 만든 1600×1200 RGB TIFF 2페이지를 사용했습니다.
첫 페이지는 빨간 블록, 둘째 페이지는 파란 블록으로 구분했습니다. 외부 모듈 폴더는 변경하지 않았습니다.

| 입력/옵션 | 결과 | 출력 | 프로세스 경과 시간(단일 실행) |
| --- | --- | --- | --- |
| -page 0, PNG | 종료 0 / result=0 | 800×600, 빨간 픽셀 #ff0000 | 216ms |
| -page 1, JPG | 종료 0 / result=0 | 800×600, 파란 픽셀 #0000fe | 121ms |
| -page 2, PNG | 종료 -8 / result=-8 | 이미지 미생성 | 84ms |

사용 옵션: -page N -odir OUTPUT -otype png/jpg -oscalesize 800 -oscalename preview.png/jpg -oscaleonly -f INPUT.
출력 이미지는 ImageIO로 다시 읽어 크기와 색상으로 해당 페이지인지 검증했습니다.
프로세스 작업 폴더는 프로젝트의 .tools/img2tile-probe이며 필요한 resource 파일만 복사했습니다.
종료 코드를 정상·오류 모두 확인했고, 실패 시 출력 파일 존재만으로 성공 판정하지 않아야 합니다.

이 수치는 합성 소형 TIFF의 단일 실행이며 P50/P95나 실제 서비스 속도 기준선이 아닙니다.
HEIC·JPEG 2000·타일·대형/압축 TIFF·ICC·EXIF·투명도·손상 파일은 별도 검증이 필요합니다.
생성된 doc.inf는 두 페이지 모두 동일한 .tif;1;1 문자열이므로 이를 문서 전체 페이지 수로 신뢰하지 않습니다.

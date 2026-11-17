// Korean guidance for server/client error codes. Add an entry whenever a new code can reach the viewer.
export const messages={AUTHENTICATION_REQUIRED:'로그인이 필요합니다. 개발 환경은 SimpleImgViewer 실행 구성을 사용해 주세요.',CSRF_INVALID:'보안 세션이 만료됐습니다. 새로고침해 주세요.',
  CACHE_CAPACITY_REACHED:'미리보기 저장 공간이 부족합니다. 관리자에게 공간 정리를 요청해 주세요. 원본 파일은 보관되어 있습니다.',
  ORIGINAL_CAPACITY_REACHED:'원본 보관 공간의 한도에 도달했습니다. 관리자에게 문의해 주세요. 새 파일은 저장하지 않았습니다.',
  INSUFFICIENT_STORAGE:'서버의 남은 저장 공간이 부족합니다. 관리자에게 문의해 주세요.',
  RUNTIME_NOT_ACTIVE:'파일 처리 모듈이 준비되지 않았습니다. 관리자에게 문의해 주세요.',
  RUNTIME_TAMPERED:'파일 처리 환경의 검증에 실패했습니다. 관리자에게 문의해 주세요.',
  RUNTIME_EXECUTION_REJECTED:'파일 처리 모듈을 시작할 수 없습니다. 관리자에게 문의해 주세요.',
  PLAYBACK_DISABLED:'재생 모듈이 비활성화되어 있습니다. FFmpeg 설치·설정을 확인해 주세요.',URL_NOT_ALLOWED:'허용된 파일 서버 주소와 경로인지 확인해 주세요.',URL_IMPORT_DISABLED:'URL 파일 열기가 설정에서 꺼져 있습니다.',
  FILE_TOO_LARGE:'파일 크기가 허용 범위를 초과했습니다.',UNSUPPORTED_MEDIA:'현재 처리할 수 없는 파일 형식입니다.',CONVERSION_UNSUPPORTED_PROFILE:'디코더가 지원하지 않는 이미지 구성입니다.',EDIT_CONFLICT:'다른 변경이 저장되었습니다. 파일을 다시 선택해 최신 상태를 읽어 주세요.',PREVIEW_QUEUE_FULL:'이미지 처리 중입니다. 잠시 후 다시 시도해 주세요.',
  INVALID_LAUNCH_URL:'열기 주소가 올바르지 않습니다. url은 하나만 지정하고 sessionId·mediaId와 함께 쓰지 마세요.',FILE_COUNT_LIMIT:'한 번에 열 수 있는 파일은 최대 100개입니다.',
  UPLOAD_BUSY:'다른 파일을 올리는 중입니다. 잠시 후 다시 시도해 주세요.',PLAYBACK_QUEUE_FULL:'다른 재생 파일을 준비하는 중입니다. 잠시 후 다시 시도해 주세요.',
  MEDIA_CHANGED:'파일이 변경되었습니다. 파일을 다시 선택해 주세요.',STALE_COORDINATES:'편집 정보가 현재 파일과 맞지 않습니다. 파일을 다시 선택해 주세요.',FEATURE_DISABLED:'이 기능은 설정에서 꺼져 있습니다.',
  INVALID_MEDIA:'파일을 해석할 수 없거나 허용 범위(길이·해상도·채널 등)를 넘습니다.',INVALID_PLAYBACK_OUTPUT:'재생용 사본을 만들지 못했습니다.',PLAYBACK_FAILED:'재생 준비에 실패했습니다.',
  PLAYBACK_TIMEOUT:'재생 준비 시간이 초과되었습니다.',MEDIA_BUSY:'파일을 처리하는 중이라 지금은 삭제할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  SESSION_NOT_FOUND:'세션이 만료되었거나 열 수 있는 파일이 없습니다. 파일을 다시 열어 주세요.',MEDIA_NOT_FOUND:'파일을 찾을 수 없습니다. 삭제되었거나 열 수 없는 파일입니다.',CONVERSION_TIMEOUT:'이미지 처리 시간이 초과되었습니다.'};
// Short form for inline notices: the guidance, or the raw code when none exists.
export const brief=code=>messages[code]||code;
// Full-screen error form keeps the existing generic prefix for unmapped codes.
export const describe=code=>messages[code]||'요청을 완료하지 못했습니다. '+code;

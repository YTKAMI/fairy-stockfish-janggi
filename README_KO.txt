장기엔진판 Windows EXE 빌드 패키지
====================================

이 패키지는 Windows용 EXE를 GitHub Actions에서 자동 빌드하기 위한 최소 파일입니다.
BAT 파일을 사용자 PC에서 실행할 필요는 없습니다.

포함 파일
- launcher.py: EXE 시작점
- janggi_server.py: 장기판 로컬 서버
- 장기엔진판.html: 장기판 UI
- fairy-stockfish_x86-64-avx512.exe: Fairy-Stockfish 엔진
- janggi_settings.json: 엔진 설정
- .github/workflows/build-windows.yml: Windows 자동 빌드 설정

빌드 방법
1. GitHub 저장소 YTKAMI/fairy-stockfish-janggi 를 엽니다.
2. Add file > Upload files 에서 이 폴더 안의 파일들을 업로드합니다.
   .github/workflows/build-windows.yml 파일은 반드시 해당 폴더 경로로 올립니다.
3. Actions 탭 > Build Windows EXE > Run workflow 를 누릅니다.
4. 빌드가 끝나면 실행 결과 아래 Artifacts의 JanggiEngine-Windows.zip을 다운로드합니다.
5. ZIP 압축을 풀고 JanggiEngine.exe를 실행합니다.

주의
- 실제 Windows 빌드는 GitHub Actions의 Windows 실행기에서 수행됩니다.
- EXE와 HTML, Fairy-Stockfish 엔진 파일은 같은 폴더에 포함됩니다.
- 이 빌드는 Fairy-Stockfish 장기판(기본 CPU 엔진) 실행용입니다.
- 별도 Python/PyTorch/CUDA가 필요한 신경망 학습 및 카카오 자동대국 기능은 이 EXE에 포함하지 않았습니다.
- AVX-512 엔진 파일은 CPU가 AVX-512를 지원해야 실행됩니다. 실행되지 않으면 엔진 파일을 AVX2 호환 빌드로 교체해야 합니다.

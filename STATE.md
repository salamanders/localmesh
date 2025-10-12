# State and Goal

## 1. Goal
To prove that a WebView in the app can use permission-gated APIs (like motion sensors) without a user click, by having the native code grant permissions and trigger the necessary JavaScript.

## 2. Strategy
The strategy is to add a text input box to the main `index.html` page. When text is entered into this box, the JavaScript will use it as a `sourceNodeId` query parameter on its requests. This will simulate a request from a peer, causing the app's server to process the request locally instead of broadcasting it. This allows for end-to-end testing of the local display logic directly through the UI.

## 3. Where We Got Stuck
The gemini-cli agent failed to correctly implement this strategy. It made several errors, including:
- Misunderstanding the core broadcast-vs-local execution logic of the server.
- Incorrectly using file modification tools, leading to failed or incorrect changes.
- Misinterpreting logs and incorrectly announcing success when the desired outcome had not occurred.
- Failing to follow a step-by-step verification process, leading to confusion.

## 4. MUST ALWAYS FOLLOW
- Lack of errors is not proof of success: the app may have stopped.  The only proof of something working is a logcat of the thing working.
- "Pair Debugging" is an option - asking the user to click on things.
- "Controlling the App Through ADB" is a great option.
- The process ID changes every run. Assume that it did. Also assume the log gets cluttered quickly.

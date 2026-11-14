# DeepSeek Harness for Android

DeepSeek Harness runs its orchestration, tools, sessions, and plugin runtime on your Android device. You can install original Cordis plugins locally; a plugin can run when its required upstream services and Android-compatible dependencies are available. Model inference uses the provider API configured in the app.

## Install

1. Open the [GitHub Releases](https://github.com/ComiCine/deepseek-harness-mobile/releases) page and download the APK for your device.
2. Open the downloaded APK and follow Android's installation prompts.
3. Launch **DeepSeek Harness**.

## Configure a model

1. Open **Settings**.
2. Enter the HTTPS model API URL, model ID, and API key for your provider.
3. Save the settings and securely save the API key.
4. Return to **Chat** and start a session.

The API key is protected with Android Keystore. Requests for model inference are sent to the endpoint you configure.

## Chat and sessions

- Enter a task in the chat box and send it to the model.
- Review tool details and approve each tool call that requires confirmation.
- Use the stop button to cancel a running task. Failed or interrupted turns can be retried.
- Open **Sessions** to switch sessions, rename or delete them, or import and export session JSON.
- Open **Logs** to review model requests, tool calls, approvals, errors, and task status.

## Install original plugins

1. Open **Plugins**.
2. Enter an original npm package name, such as `@deepseek-ai/dsh-tool-todo@0.2.2-rc.3`, or use the top-right button to select an upstream `.tgz` package.
3. Wait for npm to resolve the package and its dependencies on the device.
4. Review the package details, configure its JSON settings if needed, and enable it.

Newly installed plugins are disabled until you enable them. Plugin code runs in the app process and can access the app sandbox and network; install packages from sources you trust. Tool calls made through plugins are subject to the app's approval flow.

## Workspace and files

The **Workspace** screen manages files in the app-private workspace. Use the import button to copy a file into the workspace, open a file to preview it, and use the export or delete action for an individual item. Files are not uploaded automatically.

## Language and accessibility

Open **Settings → App language** to choose **System default**, **English**, or **Simplified Chinese**. The app also appears in Android's per-app language settings. TalkBack labels are provided for navigation and icon actions.

## Data and legal information

Conversations, sessions, task history, plugin settings, workspace files, and logs are stored in the app's local storage. A model provider receives the requests needed for inference. Plugins may make their own network requests when their functionality requires them.

See [Licensing](LICENSING.md), [Copyright and Licensing](COPYRIGHT.md), [Security Vulnerability Disclosure Policy](SECURITY.md), [LICENSE](LICENSE), [Additional Terms](ADDITIONAL_TERMS.md), and [Third-Party Notices](THIRD_PARTY_NOTICES.md).

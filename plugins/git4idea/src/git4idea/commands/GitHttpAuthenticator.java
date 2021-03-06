/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.commands;

import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.passwordSafe.PasswordSafeException;
import com.intellij.ide.passwordSafe.impl.PasswordSafeImpl;
import com.intellij.ide.passwordSafe.ui.PasswordSafePromptDialog;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.AuthData;
import com.intellij.util.io.URLUtil;
import com.intellij.vcsUtil.AuthDialog;
import git4idea.jgit.GitHttpAuthDataProvider;
import git4idea.remote.GitRememberedInputs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * <p>Handles "ask username" and "ask password" requests from Git:
 *    shows authentication dialog in the GUI, waits for user input and returns the credentials supplied by the user.</p>
 * <p>If user cancels the dialog, empty string is returned.</p>
 * <p>If no username is specified in the URL, Git queries for the username and for the password consecutively.
 *    In this case to avoid showing dialogs twice, the component asks for both credentials at once,
 *    and remembers the password to provide it to the Git process during the next request without requiring user interaction.</p>
 * <p>New instance of the GitAskPassGuiHandler should be created for each session, i. e. for each remote operation call.</p>
 *
 * @author Kirill Likhodedov
 */
class GitHttpAuthenticator {

  private static final Logger LOG = Logger.getInstance(GitHttpAuthenticator.class);
  private static final Class<GitHttpAuthenticator> PASS_REQUESTER = GitHttpAuthenticator.class;

  @NotNull  private final Project myProject;
  @Nullable private final ModalityState myModalityState;
  @NotNull  private final String myTitle;

  @Nullable private String myPassword;
  @Nullable private String myPasswordKey;
  @Nullable private String myUrl;
  @Nullable private String myLogin;
  private boolean myRememberOnDisk;

  GitHttpAuthenticator(@NotNull Project project, @Nullable ModalityState modalityState, @NotNull GitCommand command) {
    myProject = project;
    myModalityState = modalityState;
    myTitle = "Git " + StringUtil.capitalize(command.name());
  }

  @NotNull
  String askPassword(@NotNull String url) {
    if (myPassword != null) {  // already asked in askUsername
      return myPassword;
    }
    String prompt = "Enter the password for " + url;
    String key = adjustHttpUrl(url);
    myPasswordKey = key;
    return PasswordSafePromptDialog.askPassword(myProject, myModalityState, myTitle, prompt, PASS_REQUESTER, key, false, null);
  }

  @NotNull
  String askUsername(@NotNull String url) {
    String key = adjustHttpUrl(url);
    AuthData authData = getSavedAuthData(myProject, key);
    String login = null;
    String password = null;
    if (authData != null) {
      login = authData.getLogin();
      password = authData.getPassword();
    }
    if (login != null && password != null) {
      myPassword = password;
      return login;
    }

    final AuthDialog dialog = new AuthDialog(myProject, myTitle, "Enter credentials for " + url, login, null, true);
    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        dialog.show();
      }
    }, myModalityState == null ? ModalityState.defaultModalityState() : myModalityState);

    if (!dialog.isOK()) {
      return "";
    }

    // remember values to store in the database afterwards, if authentication succeeds
    myPassword = dialog.getPassword();
    myLogin = dialog.getUsername();
    myUrl = key;
    myRememberOnDisk = dialog.isRememberPassword();
    myPasswordKey = makeKey(myUrl, myLogin);

    return myLogin;
  }

  void saveAuthData() {
    // save login and url
    if (myUrl != null && myLogin != null) {
      GitRememberedInputs.getInstance().addUrl(myUrl, myLogin);
    }

    // save password
    if (myPasswordKey != null && myPassword != null) {
      PasswordSafeImpl passwordSafe = (PasswordSafeImpl)PasswordSafe.getInstance();
      try {
        passwordSafe.getMemoryProvider().storePassword(myProject, PASS_REQUESTER, myPasswordKey, myPassword);
        if (myRememberOnDisk) {
          passwordSafe.getMasterKeyProvider().storePassword(myProject, PASS_REQUESTER, myPasswordKey, myPassword);
        }
      }
      catch (PasswordSafeException e) {
        LOG.error("Couldn't remember password for " + myPasswordKey, e);
      }
    }
  }

  void forgetPassword() {
    if (myPasswordKey != null) {
      try {
        PasswordSafe.getInstance().removePassword(myProject, PASS_REQUESTER, myPasswordKey);
      }
      catch (PasswordSafeException e) {
        LOG.info("Couldn't forget the password for " + myPasswordKey);
      }
    }
  }

  /**
   * If the url scheme is HTTPS, store it as HTTP in the database, not to make user enter and remember same credentials twice.
   */
  @NotNull
  private static String adjustHttpUrl(@NotNull String url) {
    String prefix = "https";
    if (url.startsWith(prefix)) {
      return "http" + url.substring(prefix.length());
    }
    return url;
  }

  @Nullable
  private static AuthData getSavedAuthData(@NotNull Project project, @NotNull String url) {
    String userName = GitRememberedInputs.getInstance().getUserNameForUrl(url);
    if (userName == null) {
      return trySavedAuthDataFromProviders(url);
    }
    String key = makeKey(url, userName);
    final PasswordSafe passwordSafe = PasswordSafe.getInstance();
    try {
      String password = passwordSafe.getPassword(project, PASS_REQUESTER, key);
      if (password != null) {
        return new AuthData(userName, password);
      }
      return null;
    }
    catch (PasswordSafeException e) {
      LOG.info("Couldn't get the password for key [" + key + "]", e);
      return null;
    }
  }

  @Nullable
  private static AuthData trySavedAuthDataFromProviders(@NotNull String url) {
    GitHttpAuthDataProvider[] extensions = GitHttpAuthDataProvider.EP_NAME.getExtensions();
    for (GitHttpAuthDataProvider provider : extensions) {
      AuthData authData = provider.getAuthData(url);
      if (authData != null) {
        return authData;
      }
    }
    return null;
  }

  /**
   * Makes the password database key for the URL: inserts the login after the scheme: http://login@url.
   */
  @NotNull
  private static String makeKey(@NotNull String url, @NotNull String login) {
    Pair<String,String> pair = URLUtil.splitScheme(url);
    String scheme = pair.getFirst();
    if (scheme != null) {
      return scheme + login + "@" + pair.getSecond();
    }
    return login + "@" + url;
  }

}

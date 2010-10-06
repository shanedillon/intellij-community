/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

/*
 * @author max
 */
package com.intellij.lang.properties;

import com.intellij.codeInsight.completion.CompletionProcess;
import com.intellij.codeInsight.completion.CompletionService;
import com.intellij.lang.DefaultWordCompletionFilter;
import com.intellij.lang.properties.parsing.PropertiesElementTypes;
import com.intellij.psi.tree.IElementType;

public class PropertiesWordCompletionFilter extends DefaultWordCompletionFilter {
  public boolean isWordCompletionEnabledIn(final IElementType element) {
    final CompletionProcess process = CompletionService.getCompletionService().getCurrentCompletion();
    if (process != null && process.isAutopopupCompletion()) {
      return false;
    }

    return super.isWordCompletionEnabledIn(element) || element == PropertiesElementTypes.PROPERTY;
  }
}
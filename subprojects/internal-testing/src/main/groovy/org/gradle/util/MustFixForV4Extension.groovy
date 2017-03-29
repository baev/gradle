/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.util

import org.spockframework.runtime.extension.AbstractAnnotationDrivenExtension
import org.spockframework.runtime.model.FeatureInfo
import org.spockframework.runtime.model.SpecInfo

class MustFixForV4Extension extends AbstractAnnotationDrivenExtension<MustFixForV4> {
    @Override
    void visitSpecAnnotation(MustFixForV4 annotation, SpecInfo spec) {
        spec.skipped |= true
    }

    @Override
    void visitFeatureAnnotation(MustFixForV4 annotation, FeatureInfo feature) {
        feature.skipped |= true
    }
}

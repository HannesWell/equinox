/*******************************************************************************
 * Copyright (c) 2010, 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/
package org.eclipse.equinox.bidi.internal.consumable;

import org.eclipse.equinox.bidi.*;
import org.eclipse.equinox.bidi.internal.BidiComplexSingle;

/**
 * Processor adapted to processing property file statements.
 * It expects the following string format:
 * <pre>
 *  name=value
 * </pre>
 */
public class BidiComplexProperty extends BidiComplexSingle {
	static final BidiComplexFeatures FEATURES = new BidiComplexFeatures("=", 1, -1, -1, false, false); //$NON-NLS-1$

	/**
	 *  This method retrieves the features specific to this processor.
	 *
	 *  @return features with one operator (equal sign), 1 special case,
	 *          LTR direction for Arabic and Hebrew, and support for both.
	 */
	public BidiComplexFeatures init(BidiComplexHelper helper, BidiComplexEnvironment env) {
		return FEATURES;
	}

}
/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.carbon.modules

import viper.silver.{ast => sil}
import viper.carbon.boogie.{LocalVar, Exp, Decl}
import viper.carbon.verifier.Verifier

/**
 * Common trait for modules.

 */
trait Module {
  /** The verifier to interact with other modules. */
  def verifier: Verifier

  /**
   * The name of this module, e.g., "Heap module"
   */
  def name: String

  /**
   * Initialize this module and register any potential components with other modules.  At the point
   * where this method is called, all modules in Verifier have been set, but the other modules
   * might not have been fully initialized (by calling this method).
   */
  def initialize() {}

  /**
   * The Boogie code that this module will insert into the preamble (optional).
   *
   * The convention is that the verifier will first translate the full SIL program, and only after the
   * translation ask all the modules to give preamble definitions.  This allows modules to first see
   * if certain features are needed, and only output parts of the preamble that are used.
   */
  def preamble: Seq[Decl] = Nil

  /**
   * The assumptions that can be made about a value of a given type (e.g. allocatedness of non-null references).
   * the "isParameter" flag can be used to select assumptions which only apply to parameters
   */
  def validValue(typ: sil.Type, variable: LocalVar, isParameter: Boolean): Option[Exp] = None

  override def toString = name
}

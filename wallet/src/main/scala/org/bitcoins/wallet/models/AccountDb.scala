package org.bitcoins.wallet.models

import org.bitcoins.core.crypto._
import org.bitcoins.core.hd._
import org.bitcoins.keymanager.util.HDUtil

/** Represents the xpub at the account level, NOT the root xpub
  * that in conjunction with the path specified in hdAccount
  * can be used to generate the account level xpub
  * m / purpose' / coin_type' / account'
  * @see https://github.com/bitcoin/bips/blob/master/bip-0044.mediawiki#path-levels
  */
case class AccountDb(xpub: ExtPublicKey, hdAccount: HDAccount) {
  def xpubVersion: ExtKeyPubVersion = xpub.version

  def xprivVersion: ExtKeyPrivVersion =
    HDUtil.getMatchingExtKeyVersion(xpubVersion)

}

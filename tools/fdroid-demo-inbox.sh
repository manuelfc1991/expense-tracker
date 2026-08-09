#!/usr/bin/env bash
#
# Fills a running emulator with an invented inbox, for the F-Droid store screenshots.
#
#   emulator -avd ours-api36 -no-window -gpu swiftshader_indirect -no-snapshot
#   adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
#   adb -s emulator-5554 shell pm grant com.manuel.ours android.permission.READ_SMS
#   adb -s emulator-5554 shell pm grant com.manuel.ours android.permission.RECEIVE_SMS
#   adb -s emulator-5554 shell cmd connectivity airplane-mode disable   # <- see below
#   ./tools/fdroid-demo-inbox.sh
#
# The screenshots are shot here rather than on a real phone for one reason: the real
# screens are somebody's bank balances, and store images are served to the open web.
#
# Two things that waste an hour if you do not know them. The AVD boots with **airplane
# mode on**, and `adb emu sms send` cheerfully answers OK while delivering nothing —
# there is no error, the message simply never exists. And the image has no Messaging app,
# so nothing else writes to the SMS provider either; what makes this work is the app's own
# broadcast receiver, which means the app must be installed and permitted *first*.
#
# Plain sender headers — the emulator console drops the
# TRAI-style "AX-KGBANK-S" form, and the parser matches on the bank code inside the
# header anyway, so KGBANK is what it would have looked at regardless.
#
# Nothing here is real: not the merchants, not the account tails, not the balances.

set -eu
send() { adb -s emulator-5554 emu sms send "$1" "$2" >/dev/null; sleep 2; }

send KGBANK "Your a/c no. XX3062 is credited for Rs.58200.00 on 01/08/26 10:12 AM by SALARY-Kerala Gramin Bank. Avl Bal Rs.61480.00"
send KGBANK "Rs.2340.00 debited from A/c XX3062 on 02-08-26 to VPA supermarket@okaxis. Avl Bal Rs.59140.00 -Kerala Gramin Bank"
send KGBANK "Rs.320.00 debited from A/c XX3062 on 03-08-26 to VPA rameshkumar@oksbi. Avl Bal Rs.58820.00 -Kerala Gramin Bank"
send KGBANK "Rs.150.00 debited from A/c XX3062 on 03-08-26 to VPA zepto@ybl. Avl Bal Rs.58670.00 -Kerala Gramin Bank"
send FEDBNK "Rs.1250.00 debited from A/c XX4657 on 04-08-26 to VPA fuelstation@ybl. Avl Bal Rs.14900.00 -Federal Bank"
send FEDBNK "Rs.780.00 debited from A/c XX4657 on 05-08-26 to VPA pharmacy@okaxis. Avl Bal Rs.14120.00 -Federal Bank"
send KGBANK "Rs.3100.00 debited from A/c XX3062 on 05-08-26 to VPA electricity@sbi. Avl Bal Rs.55570.00 -Kerala Gramin Bank"
send FEDBNK "Rs.1899.00 debited from A/c XX4657 on 06-08-26 to VPA electronics@ybl. Avl Bal Rs.12221.00 -Federal Bank"
send ICICIT "INR 2499.00 spent on ICICI Bank Card XX9012 on 06-Aug-26 at BOOKSTORE. Avl Lmt: INR 47501.00"
send ICICIT "INR 845.00 spent on ICICI Bank Card XX9012 on 07-Aug-26 at GROCERYRUN. Avl Lmt: INR 46656.00"

# The deposit 7.5 is about: money leaving a current account into somewhere it is held.
send KGBANK "Your a/c no. XX3062 is debited for Rs.20000.00 on 07/08/26 11:03 AM and credited to a/c no. XX8891 (UPI Ref no 519012345678)-Kerala Gramin Bank"

echo "sent 11"

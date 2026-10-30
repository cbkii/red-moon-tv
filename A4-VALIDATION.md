# A4 testing and diagnostic collection

Use the [complete A4 validation guide](docs/A4-VALIDATION.md) for ADB setup,
package selection, the guided read/probe session, performance comparisons and
physical acceptance criteria.

The standalone [ADB collector](scripts/a4-collect.py) needs Python 3.8+ and ADB
on Termux or a workstation. Run it there, **outside `adb shell`**. It saves a
ZIP on the machine running the script and never stages files on the TV.

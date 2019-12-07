# Shelf

Firefox bookmarks sync extension.

# Design

## Full import of peer's bookmarks

Imported bookmarks are stored under the special
folder created during setup. The stored state holds
the id for the folder.

Every peer has its own subfolder for its bookmark tree.
Every time there is bookmark tree version update for
a peer, another peer folder with the same name is created
(Firefox allows folders with the same names) and the
new tree is imported. As soon as the import is finished,
any previous folders are removed.

# Debug

If using the extension while in private mode,
explicit permission has to be granted via
extention management menu.

# Running tests

# Usage

A the moment the extension is not published and
should be installed on every browser startup
manually via `about:addons` page.

When started, the extension shows a lighning-like
icon in the top right corner of the browser.

When pressed, there is a PIN prompt.
Pin is used to securely store an encryption key
locally. The key itself is used to encrypt data
on remote storage.

At the first launch the PIN is freely
picked by the user, the key is genrated and
and stored protected by the PIN.

Subsequent PIN promts require user to submit
the previously picked PIN to retrieve the key
from local storage.
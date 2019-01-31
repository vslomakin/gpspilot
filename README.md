To build the project it needed to place `keys.property` file in the project root directory with the following content:

```properties
# Kip this file outside of repository

google_map=YOUR-GOOGLE-MAP-API-KEY

# APK signing
keystore_path=PATH-TO-KEYSTORE-RELATIVE-TO-THE-APP-FOLDER
keystore_store_password=KEYSTORE-ITSELF-PASSWORD
keystore_key_alias=KEY-ALIAS-NAME
keystore_key_password=KEYSTORE-KEY-PASSWORD

crashlytics_key=YOUR-CRASHLYTICS-API
```


# License

```
MIT License

Copyright (c) 2019 Vasily Lomakin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
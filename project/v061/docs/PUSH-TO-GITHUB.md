# GitHub-এ পুশ করার গাইড (v1.3.4)

> **✅ হয়ে গেছে (২০২৬-১১-০১):** কোড এখন
> <https://github.com/alaqsaforce-hub/reimagined-octo-umbrella> — `main`, ২২ কমিট, HEAD `f0b16ac`।
> অ্যাকাউন্ট: **`alaqsaforce-hub`** (এই অ্যাকাউন্টের টোকেনই লাগে; অন্য অ্যাকাউন্টে ৪০৩)।
> টোকেন শুধু push-এর মুহূর্তে remote URL-এ ছিল, পরে `git remote set-url` দিয়ে সরানো হয়েছে
> (`.git/config`-এ `github_pat` ম্যাচ = ০)। **চ্যাটে দেওয়া টোকেনটা GitHub-এ গিয়ে revoke করে দিন।**
>
> পরের বার (একই সেশনে টোকেন আবার দিলে):
> ```bash
> git remote add origin https://alaqsaforce-hub:<TOKEN>@github.com/alaqsaforce-hub/reimagined-octo-umbrella.git
> git push origin main
> git remote set-url origin https://github.com/alaqsaforce-hub/reimagined-octo-umbrella.git
> ```
> Sandbox-এ credential ফাইল snapshot থেকে বাদ যায়, তাই প্রতি সেশনে টোকেন আবার লাগবে।

## পুরোনো অবস্থা (কেন প্রথমে পুশ হচ্ছিল না)

## কেন পুশ হচ্ছিল না — আসল কারণ (যাচাই করা)

```
$ git remote -v                       → (কিছু নেই; .git/config sandbox নিয়মে snapshot থেকে বাদ যায়)
$ git push origin main                → fatal: 'origin' does not appear to be a git repository
$ (origin যোগ করে) git push origin main → fatal: could not read Username for 'https://github.com'
$ which gh → gh: command not found · ls ~/.ssh → নেই · ~/.netrc → নেই
$ GitHub connector (Arena app) → unsupported
```

অর্থাৎ **`alaqsaforce-hub/super-duper-octo-fishstick`-এ পুশ করার মতো কোনো credential এই স্যান্ডবক্সে নেই**,
আর GitHub-এর normal password দিয়ে পুশ করা যায় না (2021 থেকে personal access token বাধ্যতামূলক)।
রিপোটি পড়া যায় (`git ls-remote`, REST API), কিন্তু লেখা যায় না।

আরও একটা তথ্য: ওই রিপোতে **v0.6.3-এর পরে কিছুই নেই** — `project/v061/app/build.gradle.kts` ওখানে
`versionCode = 9 / "0.6.3"`, আর `CalendarCore.kt`/`AccessibilitySender.kt`-এর কোনো অস্তিত্ব নেই।

## পুশ করার আগে যা দরকার

একটা **fine-grained personal access token** (শুধু এই রিপোতে Contents: Read and write):
<https://github.com/settings/personal-access-tokens/new>

টোকেন দিলে এই কমান্ডেই কাজ শেষ:

```bash
cd /home/user/repo
git config user.email "dev@voicedock.local"; git config user.name "VoiceDock Dev"
git remote add origin https://<USER>:<TOKEN>@github.com/alaqsaforce-hub/super-duper-octo-fishstick.git
git push origin main            # অথবা নতুন ব্রাঞ্চ: git push origin main:voicedock-v1.3.4
```

> **সতর্কতা:** এই রিপোতে ইতিমধ্যে পুরোনো ইতিহাস (v0.6.3) আছে যা আমাদের ২০-কমিট ইতিহাসের সাথে
> সম্পর্কহীন, তাই `main`-এ পুশ করতে **force** লাগতে পারে। নিরাপদ পথ: নতুন ব্রাঞ্চে পুশ করা
> (`main:voicedock-v1.3.4`), অথবা একটা নতুন খালি রিপোতে পুশ করা।

## টোকেন ছাড়া কী কী করা যায়

| কাজ | উপায় |
|---|---|
| সোর্স + ইতিহাস | `artifacts/VoiceDock-AI-v1.3.4-source.zip`, `artifacts/VoiceDock-repo-v1.3.4.bundle` |
| পুরো ইতিহাস ফেরানো | `git clone VoiceDock-repo-v1.3.4.bundle voicedock` |
| ম্যানুয়ালি আপলোড | ফাইলগুলো এখন Drive ফোল্ডারেও আছে (নিচের তালিকা) |

## Drive-এ এই মুহূর্তে যা আপলোড করা হয়েছে

ফোল্ডার: `1zTDdiKI19CCEea-UWhOSgBu28-ZU_cc9`

| ফাইল | সাইজ |
|---|---|
| `VoiceDock-AI-v1.3.4-debug.apk` | 19,228,461 B |
| `VoiceDock-repo-v1.3.4.bundle` | 3,352,734 B |
| `VoiceDock-AI-v1.3.4-source.zip` | 1,827,257 B |
| `LIMITATIONS-v1.3.4.md` | 14,955 B |
| `V1.3.4-CHANGES.md` | 6,729 B |
| `TEST-RESULTS-v1.3.4.md` | 4,072 B |
| `BUILD-REPORT-v1.3.4.md` | 1,755 B |

## Sandbox-এর সীমা মনে রাখুন

Sandbox-এ credential ফাইল (`.git/config`, `.netrc`, `.git-credentials`) snapshot থেকে বাদ যায় — তাই
টোকেন দিয়ে পুশ করলেও **পরের সেশনে সেটা আর থাকবে না**। স্থায়ী সমাধান একটাই: রিপোতে একবার পুশ হয়ে গেলে
সেটাই আসল ঠিকানা, পরের বার শুধু `git pull`।

# Genesis Setup

This doc describes that standard genesis process for a new convex network

 



```
Linux genesis-peer 6.1.0-27-cloud-amd64 #1 SMP PREEMPT_DYNAMIC Debian 6.1.115-1 (2024-11-01) x86_64

The programs included with the Debian GNU/Linux system are free software;
the exact distribution terms for each program are described in the
individual files in /usr/share/doc/*/copyright.

Debian GNU/Linux comes with ABSOLUTELY NO WARRANTY, to the extent
permitted by applicable law.
```

## Genesis Setup Commands run

### Install Java

```
sudo apt update
wget https://download.oracle.com/java/23/latest/jdk-23_linux-x64_bin.deb
sudo dpkg -i jdk-21_linux-x64_bin.deb
```

### Set up Convex

Upload `convex.jar` to home directory

Run or add line to `.bashrc`

```
alias convex="java -jar ~/convex.jar"
```

### Upload keystore




### Managing with screen

Start screen session

```
screen
```

Run peer

```
convex peer start
```

Detach with Ctrl-a + Ctrl-d 

Back to screen with:

```
screen -r
```
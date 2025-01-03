# Genesis Setup

This doc describes the standard genesis process for a new convex network

For a new Server on Google Cloud

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
sudo dpkg -i jdk-23_linux-x64_bin.deb
```

### Set up Convex

Upload `convex.jar` to home directory

Run or add line to `.bashrc`

```
alias convex="java -jar ~/convex.jar"
```

### Upload keystore


### Critical public keys:

Genesis/Admin Key: `0xc1d3b0104d55ddf7680181a46e93422e49e2ea9298e37794860f1ef1128427f7`
Governance key: `0x12EF73ee900eD1FE78A188f59bF8CedE467bAA66f5b60368aFAaA3B9521aB94d`
Genesis Peer key: `0x12EF73ee900eD1FE78A188f59bF8CedE467bAA66f5b60368aFAaA3B9521aB94d`
mikera key: `0x89b5142678bfef7a2245af5ae5b9ab1e10c282b375fa297c5aaeccc48ac97cac`


### Managing with screen

Start screen session

```
screen
```

Generate peer

```
convex peer genesis --key=0xc1 --peer-key=0xd6 --governance-key=0x12EF73ee900eD1FE78A188f59bF8CedE467bAA66f5b60368aFAaA3B9521aB94d
```

Run peer

```
convex peer start --url "peer.convex.live:18888" --peer-key 0xd6 --key 0xc1
```

Detach with Ctrl-a + Ctrl-d 

Back to screen with:

```
screen -r
```

### nginx setup

```
sudo apt install nginx
```

Configuration file:

```

```


### For Let's Encrypt certificates

Certbot dependencies

```
sudo apt install python3 python3-venv libaugeas0
sudo python3 -m venv /opt/certbot/
sudo /opt/certbot/bin/pip install --upgrade pip
```

Run certbot:

```
sudo certbot certonly --standalone

```
cp 
Results:

```
Successfully received certificate.
Certificate is saved at: /etc/letsencrypt/live/peer.convex.live/fullchain.pem
Key is saved at:         /etc/letsencrypt/live/peer.convex.live/privkey.pem
```

Copy and rename files:

```
mkdir ~/.convex/ssl
sudo cp /etc/letsencrypt/live/peer.convex.live/fullchain.pem ~/.convex/ssl
sudo cp /etc/letsencrypt/live/peer.convex.live/privkey.pem ~/.convex/ssl
sudo chown -R $USER ~/.convex/ssl 
mv ~/.convex/ssl/fullchain.pem ~/.convex/ssl/certificate.pem
mv ~/.convex/ssl/privkey.pem ~/.convex/ssl/private.pem
```


### Adding a second peer

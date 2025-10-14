# Convex Peer Deployment

This guide assumes you want to run a Convex peer on an internet-accessible Linux Server

## Setup

### Install JDK

Install JDK according to your system instruction. 

JDK 25 is recommended, however everything should work on Java 21+.


### Obtain convex.jar

Convex is available as a pre-build `.jar` file distributed at 


### Run Caddy

Create/edit a CaddyFile as follows (customise as required)

This is usually placed in `etc/caddy/Caddyfile`

```Caddyfile
# Global options
{
	email admin@your.org
}

peer.your.org {
	# Send requests to 8080 (including HTPPs)
	reverse_proxy :8080
}
```

Start caddy:

```bash
sudo systemctl start caddy
```

Peer server should be live on https://peer.your.org

### Screen commands

It may be helpful to use `screen` to run the peer process with a separate terminal.

Start a new screen:

```
screen -S <name>
```

List available screens:

```bash
screen -ls
```

Reattach to a existing screen session

```
screen -r 
```

Detach from a screen with `Crtl+C Ctrl+D`

### Protonet network info

`peer.convex.live` peer public key:

```
d6ef2d429b73ef1c78d9e46d87feb9d9535a991b8102099f54ed243f1e557d42
```
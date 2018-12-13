import socket

def listen():
  s = socket.socket(socket.AF_INET,socket.SOCK_RAW,socket.IPPROTO_UDP)
  s.setsockopt(socket.SOL_IP, socket.IP_HDRINCL, 1)
  while 1:
    s.sendto(b'shawani', ('10.15.28.160', 22))

listen()

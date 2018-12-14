#!/usr/bin/env python3

import time, socket, struct, select, random, os

# From /usr/include/linux/icmp.h; your milage may vary.
ICMP_ECHO_REQUEST = 8 # Seems to be the same on Solaris.
ICMP_ECHO_REPLY = 0

FILE_REQ = 'icmp_req2.bin'
FILE_REP = 'icmp_rep2.bin'
FILE_REQ_NOTIFY = 'icmp_req2.bin.notify'
FILE_REP_NOTIFY = 'icmp_rep2.bin.notify'

def checksum(source_string):
    # I'm not too confident that this is right but testing seems to
    # suggest that it gives the same answers as in_cksum in ping.c.
    sum = 0
    count_to = (len(source_string) / 2) * 2
    count = 0
    while count < count_to:
        this_val = source_string[count + 1] * 256 + source_string[count]
        sum = sum + this_val
        sum = sum & 0xffffffff # Necessary?
        count = count + 2
    if count_to < len(source_string):
        sum = sum + source_string[len(source_string) - 1]
        sum = sum & 0xffffffff # Necessary?
    sum = (sum >> 16) + (sum & 0xffff)
    sum = sum + (sum >> 16)
    answer = ~sum
    answer = answer & 0xffff
    answer = answer >> 8 | (answer << 8 & 0xff00)
    return answer

def create_packet(packet_id, seq, data, type):
    """Create a new echo request packet based on the given "id"."""
    # Header is type (8), code (8), checksum (16), id (16), sequence (16)
    header = struct.pack('!bbHHh', type, 0, 0, packet_id, seq)
    # Calculate the checksum on the data and the dummy header.
    my_checksum = checksum(header + data)
    # Now that we have the right checksum, we put that in. It's just easier
    # to make up a new header than to stuff it into the dummy.
    header = struct.pack('!bbHHh', type, 0, my_checksum, packet_id, seq)
    return header + data

def main():
    HOST = socket.gethostbyname(socket.gethostname())
    my_socket = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP)
    my_socket.setsockopt(socket.IPPROTO_IP, socket.IP_HDRINCL, 1)
    my_socket.bind((HOST, 0))
    my_socket.ioctl(socket.SIO_RCVALL, socket.RCVALL_ON)
    reply_socket = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP)
    while True:
        rec_packet, addr = my_socket.recvfrom(1024)
        icmp_header = rec_packet[20:28]
        type_, code, chksum, p_id, seq = struct.unpack(
            '!bbHHh', icmp_header)
        if addr[0] == HOST or type_ == ICMP_ECHO_REPLY:
            continue
        print('Notice:', addr)
        data = rec_packet[28:]
        dest_addr = socket.inet_ntoa(rec_packet[12:16])
        repack_header = struct.pack('!bbHHh', type_, code, 0, p_id, seq)
        corr_chksum = checksum(repack_header + data)
        if corr_chksum != chksum:
            continue

        with open(FILE_REQ, 'wb') as file_req:
            file_req.write(rec_packet[12:16])
            file_req.write(seq.to_bytes(2, 'big'))
            file_req.write(data)

        open(FILE_REQ_NOTIFY, 'wb').close()

        while not os.path.exists(FILE_REP_NOTIFY):
            time.sleep(0.05)
        os.remove(FILE_REP_NOTIFY)

        with open(FILE_REP, 'rb') as file_rep:
            dest_addr_ = file_rep.read(4)
            seq_ = file_rep.read(2)
            data = file_rep.read(56)
            data = data[:55] + b'X'

        packet = create_packet(p_id, seq, data, ICMP_ECHO_REPLY)
        reply_socket.sendto(packet, (dest_addr, 0))

if __name__ == '__main__':
    main()

